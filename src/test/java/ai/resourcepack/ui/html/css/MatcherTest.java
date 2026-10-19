package ai.resourcepack.ui.html.css;

import ai.resourcepack.ui.html.style.Style;
import ai.resourcepack.ui.html.style.Styler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Selector matching that the rule index must agree with. */
class MatcherTest {
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
