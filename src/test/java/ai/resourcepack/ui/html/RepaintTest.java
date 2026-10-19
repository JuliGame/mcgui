package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hover drawn as a partial repaint over the last picture must look like a fresh picture of the same state
 * (within the rounding a blur's boxes and a painter split into bands already differ by: one level), entering and
 * leaving every hover target, including where backdrop-filters blur what the hover changed, nest, or sit on the
 * canvas's edge. A margin one pixel too small shows up here as a seam.
 */
class RepaintTest {
    private static final int W = 2048, H = 1152;
    private static final double SCALE = 2;
    private static final Page.Loader OFFLINE = new Page.Loader(false, null);

    private record Walk(int steps, int partials, int worst, String where) {}

    private static int[] pixels(HtmlRenderer.Frame f) {
        return ((DataBufferInt) f.image().getRaster().getDataBuffer()).getData();
    }

    /** Elements with a :hover rule of their own whose box is on the viewport. */
    private static List<Element> targets(Page page, Document doc) {
        HtmlRenderer.Frame f = HtmlRenderer.render(page, doc, W, H, SCALE, 0, Selector.State.NONE);
        Set<Element> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var h : f.hits()) {
            for (Element e = h.element(); e != null; e = e.parent()) {
                if (page.styles.stateKey(e, false).contains(e)) seen.add(e);
            }
        }
        List<Element> out = new ArrayList<>();
        for (Element e : doc.getAllElements()) if (seen.contains(e)) out.add(e);
        return out;
    }

    /** Hovers every target in turn and out again, each time as Session would: partial over the last picture when it can. */
    private static Walk walk(Page page) {
        Document doc = page.document.clone();
        HtmlRenderer.Frame cur = HtmlRenderer.render(page, doc, W, H, SCALE, 0, Selector.State.NONE);
        List<Element> drawn = List.of();
        int steps = 0, partials = 0, worst = 0;
        String where = "";
        for (Element target : targets(page, doc)) {
            for (Element hover : new Element[]{target, null}) {
                List<Element> key = page.styles.stateKey(hover, false);
                Set<Element> moved = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Element e : drawn) if (!key.contains(e)) moved.add(e);
                for (Element e : key) if (!drawn.contains(e)) moved.add(e);
                var state = HtmlRenderer.state(hover, null);
                Repaint.Result r = moved.isEmpty() ? null : Repaint.partial(page, doc, W, H, SCALE, 0, state, cur, moved);
                HtmlRenderer.Frame fresh = HtmlRenderer.render(page, doc, W, H, SCALE, 0, state);
                steps++;
                if (r != null && r.frame() != null) {
                    partials++;
                    int[] a = pixels(r.frame()), b = pixels(fresh);
                    for (int i = 0; i < a.length; i++) {
                        if (a[i] == b[i]) continue;
                        int d = 0;
                        for (int shift = 0; shift < 32; shift += 8) d = Math.max(d, Math.abs(((a[i] >> shift) & 255) - ((b[i] >> shift) & 255)));
                        if (d > worst) {
                            worst = d;
                            where = (hover == null ? "leaving " : "entering ") + target.normalName() + "." + target.className() + " at pixel (" + i % W + ", " + i / W + ") by " + d;
                        }
                    }
                    cur = r.frame();
                } else {
                    cur = fresh;
                }
                drawn = key;
            }
        }
        System.out.printf("RepaintTest: %d hover steps, %d partial repaints, worst difference from a fresh picture %d%n", steps, partials, worst);
        return new Walk(steps, partials, worst, where);
    }

    private static void check(String name, Page page, int minPartials) {
        Walk w = walk(page);
        assertTrue(w.worst <= 1, name + ": a partial repaint differs from a fresh picture, " + w.where);
        assertTrue(w.partials >= minPartials, name + ": only " + w.partials + " of " + w.steps + " hovers were partial repaints");
    }

    private static Page file(String path) throws IOException {
        return Page.load(Path.of(path), OFFLINE);
    }

    @Test
    void shippedMenuHoversArePartialAndMatch() throws IOException {
        Walk w = walk(file("src/main/resources/pages/menu.html"));
        assertTrue(w.worst <= 1, "pages/menu: " + w.where);
        // Its tiles sit inside a backdrop-filter panel, which used to turn every hover into a whole picture.
        assertEquals(w.steps, w.partials, "pages/menu hovers that were not partial");
    }

    @Test
    void sampleMenuHoversArePartialAndMatch() throws IOException {
        Walk w = walk(file("samples/menu.html"));
        assertTrue(w.worst <= 1, "samples/menu: " + w.where);
        assertEquals(w.steps, w.partials, "samples/menu hovers that were not partial");
    }

    /** Something under two nested backdrops changes on hover, as does a button inside the inner one and the outer panel itself. */
    private static final String NESTED = "<html><head><style>body{margin:0;background:linear-gradient(90deg,#f06,#06f 50%,#0c6)}"
            + "div{position:absolute;box-sizing:border-box}"
            + ".under{left:60px;top:300px;width:300px;height:200px;background:#fc0}.under:hover{background:#00e}"
            + ".far{left:21px;top:41px;width:120px;height:80px;background:#fff}.far:hover{background:#000}"
            + ".outer{left:300px;top:40px;width:640px;height:480px;background:rgba(255,255,255,.15);backdrop-filter:blur(14px);border-radius:20px}"
            + ".outer:hover{background:rgba(255,255,255,.35)}"
            + ".inner{left:340px;top:100px;width:360px;height:300px;background:rgba(0,0,0,.2);backdrop-filter:blur(10px)}"
            + ".btn{left:381px;top:141px;width:140px;height:50px;background:#222;color:#fff;font-size:20px}.btn:hover{background:#e11;box-shadow:0 0 30px #ff0}"
            + ".tag{left:721px;top:421px;width:180px;height:60px;background:#135;color:#fff;font-size:20px}.tag:hover{background:#fa0}"
            + "</style></head><body><div class=under></div><div class=far></div><div class=outer></div><div class=inner></div>"
            + "<div class=btn>Button</div><div class=tag>Outside</div></body></html>";

    @Test
    void nestedBackdropsMatchAFreshPicture() {
        check("nested backdrops", Page.of(NESTED, Path.of("nested.html").toAbsolutePath(), OFFLINE), 3);
    }

    /** Backdrops touching the canvas's top, left and bottom-right edges, with hover targets in and beside them. */
    private static final String EDGES = "<html><head><style>body{margin:0;background:linear-gradient(135deg,#fa0,#08f 60%,#a0f)}"
            + "div{position:absolute;box-sizing:border-box;font-size:22px;color:#fff}"
            + ".tl{left:0;top:0;width:300px;height:180px;background:rgba(255,255,255,.2);backdrop-filter:blur(16px)}"
            + ".tlb{left:13px;top:13px;width:120px;height:44px;background:#123}.tlb:hover{background:#c21}"
            + ".br{right:0;bottom:0;width:320px;height:200px;background:rgba(0,0,0,.25);backdrop-filter:blur(12px)}"
            + ".brb{right:17px;bottom:17px;width:120px;height:44px;background:#321}.brb:hover{background:#1c2}"
            + ".mid{left:421px;top:201px;width:200px;height:120px;background:#0a8}.mid:hover{background:#fd0}"
            + ".strip{left:0;top:200px;width:1024px;height:60px;background:rgba(255,255,255,.1);backdrop-filter:blur(8px)}"
            + "</style></head><body><div class=mid>Mid</div><div class=strip></div><div class=tl></div><div class=tlb>Top</div>"
            + "<div class=br></div><div class=brb>Bottom</div></body></html>";

    /** A hard-edged orange bar across both pages, under everything. */
    private static final String BAR = "<body><div style='position:absolute;left:0;top:301px;width:1024px;height:123px;background:#f80'></div>";

    /** The same pages over a hard black/white edge (and an orange bar), where a blur's sampling grid shows if it moves. */
    @Test
    void hardEdgesUnderBackdropsMatchAFreshPicture() {
        // Blurs of 15, 9 and 10 px have sampling cells of 5, 3 and 3 device px: no start is a multiple of all of them by luck.
        check("nested backdrops on a hard edge", Page.of(NESTED.replace("background:linear-gradient(90deg,#f06,#06f 50%,#0c6)", "background:linear-gradient(90deg,#000 0 50%,#fff 50%)")
                .replace("blur(14px)", "blur(15px)").replace("<body>", BAR), Path.of("nested-hard.html").toAbsolutePath(), OFFLINE), 3);
        check("edge backdrops on a hard edge", Page.of(EDGES.replace("background:linear-gradient(135deg,#fa0,#08f 60%,#a0f)", "background:linear-gradient(90deg,#000 0 29%,#fff 29%)")
                .replace("blur(8px)", "blur(9px)").replace("<body>", BAR), Path.of("edges-hard.html").toAbsolutePath(), OFFLINE), 3);
    }

    @Test
    void backdropsOnTheCanvasEdgeMatchAFreshPicture() {
        check("backdrops on the edge", Page.of(EDGES, Path.of("edges.html").toAbsolutePath(), OFFLINE), 3);
    }
}
