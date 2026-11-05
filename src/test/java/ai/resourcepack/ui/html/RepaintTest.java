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

    record Walk(int steps, int partials, int worst, String where) {}

    private static int[] pixels(HtmlRenderer.Frame f) {
        return ((DataBufferInt) f.image().getRaster().getDataBuffer()).getData();
    }

    /** Elements with a :hover rule of their own whose box is on the viewport. */
    private static List<Element> targets(Page page, Document doc, double scale) {
        HtmlRenderer.Frame f = HtmlRenderer.render(page, doc, W, H, scale, 0, Selector.State.NONE);
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
        return walk(page, SCALE);
    }

    /** As {@link #walk(Page)}, with the page drawn at [scale] (a page with its own ui:height). */
    static Walk walk(Page page, double scale) {
        Document doc = page.document.clone();
        HtmlRenderer.Frame cur = HtmlRenderer.render(page, doc, W, H, scale, 0, Selector.State.NONE);
        List<Element> drawn = List.of();
        int steps = 0, partials = 0, worst = 0;
        String where = "";
        for (Element target : targets(page, doc, scale)) {
            for (Element hover : new Element[]{target, null}) {
                List<Element> key = page.styles.stateKey(hover, false);
                Set<Element> moved = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Element e : drawn) if (!key.contains(e)) moved.add(e);
                for (Element e : key) if (!drawn.contains(e)) moved.add(e);
                var state = HtmlRenderer.state(hover, null);
                Repaint.Result r = moved.isEmpty() ? null : Repaint.partial(page, doc, W, H, scale, 0, state, cur, moved, 0);
                HtmlRenderer.Frame fresh = HtmlRenderer.render(page, doc, W, H, scale, 0, state);
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

    /** The radial menu samples: hexagons cut by clip-path, and drawn as SVG polygons the page's CSS fills. */
    @Test
    void radialMenuHoversMatch() throws IOException {
        for (String name : new String[]{"samples/radial-clip.html", "samples/radial-svg.html"}) {
            Walk w = walk(file(name));
            assertTrue(w.worst <= 1, name + ": " + w.where);
        }
    }

    /** Something under two nested backdrops changes on hover, as does a button inside the inner one and the outer panel itself. */
    static final String NESTED = "<html><head><style>body{margin:0;background:linear-gradient(90deg,#f06,#06f 50%,#0c6)}"
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
    static final String EDGES = "<html><head><style>body{margin:0;background:linear-gradient(135deg,#fa0,#08f 60%,#a0f)}"
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

    /** Hovers over hard-edged stripes, level, slanted and tiled: a repaint's rectangle must place every edge where a fresh picture does. */
    @Test
    void hardEdgedStripesMatchAFreshPicture() {
        check("stripes", Page.of("<html><head><style>" + ScrollCacheTest.STRIPES_CSS + "</style></head><body>" + ScrollCacheTest.STRIPES_BODY + "</body></html>",
                Path.of("stripes.html").toAbsolutePath(), OFFLINE), 3);
    }

    /**
     * The vanilla example pages' shape: a blurred picture behind the whole page, slots that show a tooltip on hover
     * ({@code display}, so boxes appear and go), and a strip with rounded corners and a tiled gradient under them
     * at a fractional height, which a hover's rectangle cuts. Every hover was a whole picture before (TOO_BIG for
     * the blur, LAYOUT_MOVED for the tooltip); cut by a clip, the strip's rounded edge drew a seam (56 levels).
     */
    static final String TOOLTIPS = "<html><head><style>html,body{margin:0;height:100%;overflow:hidden;background:#000}"
            + ".pano{position:absolute;left:-24px;top:-24px;right:-24px;bottom:-24px;filter:blur(10px);"
            + "background:linear-gradient(90deg,#000 0 30%,#fff 30% 60%,#f80 60%),linear-gradient(#08f,#0f8)}"
            + ".shelf{position:absolute;left:40px;top:120.3px;width:900px;height:160.7px}"
            + ".shelf::after{content:'';position:absolute;left:0;right:0;bottom:0;height:18px;border-radius:3px;"
            + "background:repeating-linear-gradient(90deg,#a63 0 13px,#531 13px 32px);box-shadow:0 10px 14px rgba(0,0,0,.55)}"
            + ".slot{position:relative;display:inline-block;width:120px;height:110px;margin:0 10px;border-radius:10px;"
            + "background:linear-gradient(180deg,rgba(0,0,0,.45),rgba(0,0,0,.2));border:2px solid rgba(255,255,255,.07)}"
            + ".slot:hover{z-index:5;border-color:rgba(255,181,71,.6)}"
            + ".tip{display:none;position:absolute;left:60%;top:-14px;z-index:10;padding:6px 9px;white-space:nowrap;background:#100010;"
            + "border:2px solid #5000ff;box-shadow:0 10px 22px rgba(0,0,0,.55);color:#fff;font-size:20px}"
            + ".slot:hover .tip{display:block}"
            + "</style></head><body><div class=pano></div><div class=shelf>"
            + "<div class=slot><div class=tip>One<br>first</div></div><div class=slot><div class=tip>Two<br>second</div></div>"
            + "<div class=slot><div class=tip>Three<br>third</div></div><div class=slot><div class=tip>Four<br>fourth</div></div>"
            + "<div class=slot><div class=tip>Five<br>fifth</div></div></div></body></html>";

    @Test
    void tooltipsOverABlurredPictureArePartialAndMatch() {
        Walk w = walk(Page.of(TOOLTIPS, Path.of("tooltips.html").toAbsolutePath(), OFFLINE));
        assertTrue(w.worst <= 1, "tooltips: " + w.where);
        assertEquals(w.steps, w.partials, "tooltip hovers that were not partial");
    }

    /** Hovers [hovers] in turn (null: nothing), straight from one to the next, checking each partial repaint against a fresh picture. */
    private static Walk path(Page page, Document doc, List<Element> hovers, double[] largest) {
        HtmlRenderer.Frame cur = HtmlRenderer.render(page, doc, W, H, SCALE, 0, Selector.State.NONE);
        List<Element> drawn = List.of();
        int steps = 0, partials = 0, worst = 0;
        String where = "";
        java.util.Map<Repaint.Why, Integer> whys = new java.util.TreeMap<>();
        for (Element hover : hovers) {
            List<Element> key = page.styles.stateKey(hover, false);
            Set<Element> moved = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Element e : drawn) if (!key.contains(e)) moved.add(e);
            for (Element e : key) if (!drawn.contains(e)) moved.add(e);
            var state = HtmlRenderer.state(hover, null);
            if (moved.isEmpty()) continue;
            Repaint.Result r = Repaint.partial(page, doc, W, H, SCALE, 0, state, cur, moved, 0);
            HtmlRenderer.Frame fresh = HtmlRenderer.render(page, doc, W, H, SCALE, 0, state);
            steps++;
            whys.merge(r.why(), 1, Integer::sum);
            if (r.frame() != null) {
                partials++;
                largest[0] = Math.max(largest[0], r.changed().getWidth() * r.changed().getHeight());
                int[] a = pixels(r.frame()), b = pixels(fresh);
                for (int i = 0; i < a.length; i++) {
                    if (a[i] == b[i]) continue;
                    int d = 0;
                    for (int shift = 0; shift < 32; shift += 8) d = Math.max(d, Math.abs(((a[i] >> shift) & 255) - ((b[i] >> shift) & 255)));
                    if (d > worst) {
                        worst = d;
                        where = "hovering " + (hover == null ? "nothing" : hover.className()) + " at pixel (" + i % W + ", " + i / W + ") by " + d;
                    }
                }
                cur = r.frame();
            } else {
                cur = fresh;
            }
            drawn = key;
        }
        System.out.printf("RepaintTest: %d hover steps, %s, worst difference %d, largest repaint %.0f CSS px²%n", steps, whys, worst, largest[0]);
        return new Walk(steps, partials, worst, where);
    }

    /** Every element [css] picks, each followed by [then] (an element, or null for nothing), in document order. */
    private static List<Element> each(Document doc, String css, java.util.function.Function<Element, Element> then) {
        List<Element> out = new ArrayList<>();
        for (Element e : doc.select(css)) {
            out.add(e);
            out.add(then.apply(e));
        }
        return out;
    }

    /**
     * The crates page's shape: a card lifted on hover ({@code .card:hover { z-index: 2 }}) only so the tooltip of a
     * reward in it shows over the next card. The lift changes no pixel of the card itself, so hovering a reward repaints
     * the reward and its tooltip, not the whole card (85 ms on the vanilla crates page).
     */
    static final String LIFTED = "<html><head><style>html,body{margin:0;height:100%;overflow:hidden;background:#000}"
            + ".pano{position:absolute;left:-24px;top:-24px;right:-24px;bottom:-24px;filter:blur(10px);"
            + "background:linear-gradient(90deg,#000 0 30%,#fff 30% 60%,#f80 60%),linear-gradient(#08f,#0f8)}"
            + ".cards{position:absolute;top:60px;left:40px;display:flex;gap:16px}"
            + ".card{position:relative;width:220px;height:420px;padding:14px;background:rgba(40,40,40,.85);box-shadow:inset 2px 2px #171717}"
            + ".card:hover{z-index:2}"
            + ".reward{position:relative;height:60px;margin:8px 0;background:#555;border:2px solid #222}"
            + ".reward:hover{z-index:5;border-color:#fff}"
            + ".tip{display:none;position:absolute;left:150px;top:-10px;z-index:10;padding:6px 9px;white-space:nowrap;background:#100010;"
            + "border:2px solid #5000ff;color:#fff;font-size:20px}"
            + ".reward:hover .tip{display:block}"
            + "</style></head><body><div class=pano></div><div class=cards>"
            + ("<div class=card>" + "<div class=reward><div class=tip>A reward<br>with a long tooltip line</div></div>".repeat(4) + "</div>").repeat(3)
            + "</div></body></html>";

    @Test
    void liftedCardsRepaintOnlyTheReward() {
        Page page = Page.of(LIFTED, Path.of("lifted.html").toAbsolutePath(), OFFLINE);
        Document doc = page.document.clone();
        // In and out of every reward: each repaints the reward and its tooltip, not its card.
        double[] largest = {0};
        Walk w = path(page, doc, each(doc, ".reward", e -> null), largest);
        assertTrue(w.worst <= 1, "lifted cards: " + w.where);
        assertEquals(w.steps, w.partials, "reward hovers that were not partial");
        // A card is 248 x 448, so lifted whole it was a repaint of 344 x 544 with the margin round it.
        assertTrue(largest[0] < 0.5 * 344 * 544, "a reward hover repainted " + largest[0] + " CSS px², as much as its card");
        // Straight from each reward to the next.
        w = path(page, doc, each(doc, ".reward", e -> e.nextElementSibling()), largest);
        assertTrue(w.worst <= 1, "lifted cards, reward to reward: " + w.where);
        assertEquals(w.steps, w.partials, "reward to reward hovers that were not partial");
    }

    /**
     * Cards that overlap, lifted on hover: the lift does change pixels where a card overlaps its neighbours, and only
     * there, entering, leaving and going straight from one card's button to the next card's.
     */
    static final String OVERLAPPING = "<html><head><style>html,body{margin:0;height:100%;overflow:hidden;background:#123}"
            + ".cards{position:absolute;top:80px;left:60px;display:flex}"
            + ".card{position:relative;width:240px;height:320px;margin-right:-60px;border-radius:14px;background:linear-gradient(#468,#246);"
            + "border:3px solid #9bd;box-shadow:0 8px 20px rgba(0,0,0,.6)}"
            + ".card:nth-child(2n){top:30px}"
            + ".card:hover{z-index:3}"
            + ".card b{display:block;margin:20px;height:50px;background:#333;color:#fff}"
            + ".card b:hover{background:#c33}"
            + "</style></head><body><div class=cards>"
            + "<div class=card><b>one</b></div><div class=card><b>two</b></div><div class=card><b>three</b></div><div class=card><b>four</b></div>"
            + "</div></body></html>";

    @Test
    void overlappingLiftedCardsRepaintWhereTheyOverlap() {
        Page page = Page.of(OVERLAPPING, Path.of("overlapping.html").toAbsolutePath(), OFFLINE);
        Document doc = page.document.clone();
        double[] largest = {0};
        List<Element> hovers = each(doc, ".card b", e -> null);
        List<Element> bs = doc.select(".card b");
        for (int i = 0; i < bs.size(); i++) hovers.add(bs.get(i));
        for (int i = bs.size() - 1; i >= 0; i--) hovers.add(bs.get(i));
        hovers.add(null);
        // The cards alone (only the lift changes), in and out, then straight from one to the next and back.
        List<Element> cards = doc.select(".card");
        hovers.addAll(each(doc, ".card", e -> null));
        hovers.addAll(cards);
        for (int i = cards.size() - 1; i >= 0; i--) hovers.add(cards.get(i));
        hovers.add(null);
        Walk w = path(page, doc, hovers, largest);
        assertTrue(w.worst <= 1, "overlapping cards: " + w.where);
        assertEquals(w.steps, w.partials, "card hovers that were not partial");
    }

    /** The vanilla crates page itself: a reward's hover (its crate lifted) repaints the reward and its tooltip. */
    @Test
    void vanillaCratesHoversArePartialAndMatch() throws IOException {
        Page page = file("examples/vanilla/pages/crates.html");
        Document doc = page.document.clone();
        double[] largest = {0};
        Walk w = path(page, doc, each(doc, ".reward", e -> null), largest);
        assertTrue(w.worst <= 1, "crates: " + w.where);
        assertEquals(w.steps, w.partials, "crates hovers that were not partial");
        // A crate is 316 x 442: lifted whole, 412 x 538 with the margin round it.
        assertTrue(largest[0] < 0.5 * 412 * 538, "a reward hover repainted " + largest[0] + " CSS px², as much as its crate");
        w = path(page, doc, each(doc, ".reward", e -> e.nextElementSibling()), largest);
        assertTrue(w.worst <= 1, "crates, reward to reward: " + w.where);
        assertEquals(w.steps, w.partials, "crates reward to reward hovers that were not partial");
        // A crate's own header: only the lift changes, which changes no pixel; a token repaint stands for it.
        double[] head = {0};
        w = path(page, doc, each(doc, ".crate-head", e -> null), head);
        assertTrue(w.worst <= 1, "crates, header: " + w.where);
        assertEquals(w.steps, w.partials, "crate header hovers that were not partial");
        assertTrue(head[0] < 200 * 200, "a crate header hover repainted " + head[0] + " CSS px²");
    }
}
