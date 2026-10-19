package ai.resourcepack.ui.html.style;

import ai.resourcepack.ui.html.RenderBudget;
import ai.resourcepack.ui.html.css.CssParser;
import ai.resourcepack.ui.html.css.Selector;
import ai.resourcepack.ui.html.css.Stylesheet;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class VarSubstitutionTest {
    private static Styler style(String css, Document doc) {
        Stylesheet ua = new Stylesheet(), author = new Stylesheet();
        CssParser.parse(Styler.UA_CSS, null, u -> null, ua);
        CssParser.parse(css, null, u -> null, author);
        Styler styler = new Styler(ua, author, 1024, 576, Selector.State.NONE, null, RenderBudget.NONE);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> styler.run(doc.selectFirst("html")));
        return styler;
    }

    @Test
    void aValueThatSubstitutesToSomethingHugeIsInvalidAndCheap() {
        // Each level uses the one below three times: 3^16 copies of the base value if worked out naively.
        StringBuilder css = new StringBuilder(":root { --v0: 0123456789; --g0: green; --g1: var(--g0); ");
        for (int i = 1; i <= 16; i++) css.append("--v").append(i).append(": var(--v").append(i - 1).append(") var(--v").append(i - 1).append(") var(--v").append(i - 1).append("); ");
        css.append("} p { color: var(--g1) } b { color: red; color: var(--v16) }");
        Document doc = Jsoup.parse("<p>a <b>b</b></p>");
        Styler styler = style(css.toString(), doc);
        int green = 0xFF008000;
        assertEquals(green, styler.styles.get(doc.selectFirst("p")).color);
        // Invalid at computed-value time: color is inherited, as if unset.
        assertEquals(green, styler.styles.get(doc.selectFirst("b")).color);
    }

    @Test
    void largeValuesThatDoNotGrowBySubstitutionAreKept() {
        // A big inline image in a custom property is ordinary, and so is a long value that also uses var().
        String big = "\"" + "x".repeat(200_000) + "\"";
        Document doc = Jsoup.parse("<p>a</p><b>b</b>");
        Styler styler = style(":root { --big: " + big + "; --tail: \"!\"; --wrap: var(--big) } p { content: var(--wrap) }"
                + " b { content: " + big + " var(--tail) }", doc);
        assertEquals(big, styler.styles.get(doc.selectFirst("p")).content);
        assertEquals(big + " \"!\"", styler.styles.get(doc.selectFirst("b")).content);
    }
}
