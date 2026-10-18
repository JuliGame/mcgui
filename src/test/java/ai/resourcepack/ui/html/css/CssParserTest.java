package ai.resourcepack.ui.html.css;

import ai.resourcepack.ui.html.style.Styler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CssParserTest {
    private static final URI PAGE = URI.create("file:///site/page.html");

    @Test
    void importCyclesEndAndEachSheetIsFetchedOnce() {
        Map<URI, String> sheets = Map.of(
                URI.create("file:///site/a.css"), "@import 'a.css'; @import 'b.css'; @import url(./a.css); p { color: red }",
                URI.create("file:///site/b.css"), "@import 'a.css'; @import 'b.css'; div { color: blue }");
        AtomicInteger fetches = new AtomicInteger();
        Stylesheet out = new Stylesheet();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> CssParser.parse("@import 'a.css'; @import 'a.css'; span { color: green }", PAGE, u -> {
            fetches.incrementAndGet();
            return sheets.get(u);
        }, out));
        // Each import of a.css reads a.css and b.css once (their imports back up the chain are cycles), then the page's own rule.
        assertEquals(5, out.size());
        assertEquals(2, fetches.get());
    }

    @Test
    void importsFanningOutIntoNewSheetsStop() {
        AtomicInteger fetches = new AtomicInteger();
        Stylesheet out = new Stylesheet();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> CssParser.parse("@import 's.css';", PAGE, u -> {
            int n = fetches.incrementAndGet();
            StringBuilder css = new StringBuilder();
            for (int i = 0; i < 10; i++) css.append("@import 's").append(n).append('-').append(i).append(".css'; ");
            return css.append("p { color: red }").toString();
        }, out));
        assertTrue(fetches.get() <= 64, "fetched " + fetches.get());
    }

    @Test
    void aSheetImportedUnderTwoMediaAppliesUnderEither() {
        Map<URI, String> sheets = Map.of(
                URI.create("file:///site/wide.css"), "@import 'theme.css';",
                URI.create("file:///site/narrow.css"), "@import 'theme.css';",
                URI.create("file:///site/theme.css"), "p { color: green }");
        Stylesheet author = new Stylesheet();
        CssParser.parse("@import url('wide.css') (min-width: 1000px); @import url('narrow.css') (max-width: 999px);", PAGE, sheets::get, author);
        for (int width : new int[]{500, 1200}) {
            Document doc = Jsoup.parse("<p>a</p>");
            Styler styler = new Styler(new Stylesheet(), author, width, 576, Selector.State.NONE, PAGE);
            styler.run(doc.selectFirst("html"));
            assertEquals(0xFF008000, styler.styles.get(doc.selectFirst("p")).color, "at " + width + " px");
        }
    }
}
