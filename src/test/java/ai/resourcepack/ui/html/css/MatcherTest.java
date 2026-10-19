package ai.resourcepack.ui.html.css;

import ai.resourcepack.ui.html.style.Style;
import ai.resourcepack.ui.html.style.Styler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule index and the ancestor filter only skip work: for every element and pseudo-element they
 * must find exactly the rules that trying every rule's selector finds.
 */
class MatcherTest {
    private static final String[] SELECTORS = {
            ".a.b.c", ".c.b", ".B", ".a .b", ".a > .b", ".a + .b", ".a ~ .b", ".a + .b .c", ".a ~ .b > .c", ".b.A",
            "#x", "#X", "#x .a", "div#x", "#x > p.b span", "[data-x]", "[data-x=\"1\"]", "[data-X] .c", "[data-x] > *", "*[class~=a]",
            "[CLASS~=c] span", "[lang|=en] .c", "a[href^=http]", "[abs\\:href]", "[class] [class] [class] span",
            ":is(.a, #y) p", ":where(.c) > span", ":not(.a)", "p:not(.b .a)", ":is(.a .b)", "section :is(.c) span", ":is(#x, .zz) li",
            "::before", ".a::after", "*::before", "p::marker", "li::marker", "input::placeholder", "::first-line", ".c::after",
            ":root", ":root .a", "html.Root body", "html.root .b", "body > div:first-child", "li:nth-child(2n+1)", "li:last-child", "ul li + li",
            ".a:hover", ".a:hover .b", ".a:hover + .b", ":hover > p", ":not(:hover) > .c", ".A:active", "li:hover::before",
            "span", "div div div", "div > div > span", "*", "* > html", "body *", "#x *::after",
    };
    private static final String BODY = """
            <html class="Root" lang="en-GB"><body>
            <div id="x" class="a b c" data-x="1">
              <p class="b A">one <span class="c">s</span></p>
              <p class="B">two</p>
              <section data-X="2"><div class="a"><div><span class="c b">deep</span></div></div></section>
              <ul><li class="a">1</li><li class="b">2</li><li class="c">3</li></ul>
              <input placeholder="type" class="C">
              <a href="http://e.com" class="a">l</a>
            </div>
            <div id="X" class="c"><span>t</span><p class="a"><span class="b">u</span></p></div>
            <div id="y" class="b"><p class="c">v</p></div>
            </body></html>""";

    private static Stylesheet sheet() {
        StringBuilder css = new StringBuilder();
        for (String s : SELECTORS) css.append(s).append(" { color: red }\n");
        css.append("@media (min-width: 500px) { .a .b, [data-x] { color: blue } }\n@media (max-width: 10px) { .a, * { color: blue } }\n");
        Stylesheet out = new Stylesheet();
        CssParser.parse(css.toString(), null, u -> null, out);
        return out;
    }

    @Test
    void theIndexAndFilterFindWhatTryingEveryRuleFinds() {
        Stylesheet sheet = sheet();
        assertEquals(SELECTORS.length + 4, sheet.size(), "every selector read");
        for (String doctype : new String[]{"<!doctype html>", ""}) {
            Document doc = Jsoup.parse(doctype + BODY);
            boolean quirks = doc.quirksMode() == Document.QuirksMode.quirks;
            assertEquals(doctype.isEmpty(), quirks);
            List<Element> all = doc.getAllElements();
            Element li = doc.selectFirst("li.a"), span = doc.selectFirst("span.c");
            Selector.State[] states = {Selector.State.NONE, new Selector.State(chain(li), Set.of()), new Selector.State(chain(span), Set.of(span.parent()))};
            for (Selector.State state : states) {
                Matcher m = new Matcher(state, quirks, 1024, 576);
                m.ancestors.push(doc);
                check(sheet, m, doc.selectFirst("html"), state, quirks);
            }
            assertTrue(all.size() > 20);
        }
    }

    /** Walks [e]'s tree as the Styler does, checking every element and kind. */
    private static void check(Stylesheet sheet, Matcher m, Element e, Selector.State state, boolean quirks) {
        for (int kind = 0; kind < Stylesheet.KINDS; kind++) {
            Set<Stylesheet.Rule> expected = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Stylesheet.Rule r : sheet.rules()) {
                if (Stylesheet.kind(r.selector().pseudoElement) != kind) continue;
                if (r.media() != null && !r.media().matches(1024, 576)) continue;
                if (r.selector().matches(e, state, quirks)) expected.add(r);
            }
            List<Stylesheet.Rule> matched = new ArrayList<>(), dynamic = new ArrayList<>();
            m.collect(sheet, e, kind, matched, dynamic);
            List<Stylesheet.Rule> found = new ArrayList<>(matched);
            for (Stylesheet.Rule r : dynamic) if (m.matches(r, e)) found.add(r);
            Set<Stylesheet.Rule> got = Collections.newSetFromMap(new IdentityHashMap<>());
            got.addAll(found);
            assertEquals(found.size(), got.size(), "a rule found twice for " + e.cssSelector());
            assertEquals(names(expected), names(got), "kind " + kind + " of " + e.cssSelector() + (quirks ? " in quirks mode" : ""));
        }
        m.ancestors.push(e);
        for (Element c : e.children()) check(sheet, m, c, state, quirks);
        m.ancestors.pop(e);
    }

    private static Set<String> names(Set<Stylesheet.Rule> rules) {
        Set<String> out = new HashSet<>();
        for (Stylesheet.Rule r : rules) out.add(r.order() < SELECTORS.length ? SELECTORS[r.order()] : "@media rule " + r.order());
        return out;
    }

    private static Set<Element> chain(Element e) {
        Set<Element> out = new HashSet<>();
        for (Element p = e; p != null; p = p.parent()) out.add(p);
        return out;
    }

    @Test
    void idsAndClassesIgnoreCaseOnlyInQuirksMode() {
        Stylesheet sheet = new Stylesheet();
        CssParser.parse(".Foo { color: rgb(0, 128, 0) } #Bar { background-color: rgb(0, 0, 255) } .X .y { font-size: 30px }", null, u -> null, sheet);
        String body = "<div class='x'><p class='foo Y' id='bar'>a</p></div>";
        for (boolean quirks : new boolean[]{false, true}) {
            Document doc = Jsoup.parse((quirks ? "" : "<!doctype html>") + body);
            Styler s = new Styler(new Stylesheet(), sheet, 1024, 576, Selector.State.NONE, null);
            s.run(doc.selectFirst("html"));
            Style p = s.styles.get(doc.selectFirst("p"));
            assertEquals(quirks ? 0xFF008000 : 0xFF000000, p.color, "class, quirks " + quirks);
            assertEquals(quirks ? 0xFF0000FF : 0, p.backgroundColor, "id, quirks " + quirks);
            assertEquals(quirks ? 30 : 16, p.fontSize, 1e-9, "ancestor class, quirks " + quirks);
        }
    }
}
