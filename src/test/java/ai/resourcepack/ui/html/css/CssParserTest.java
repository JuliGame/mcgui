package ai.resourcepack.ui.html.css;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CssParserTest {
    @Test
    void sheetsThatImportEachOtherAreEachReadOnce() {
        URI a = URI.create("file:///site/a.css"), b = URI.create("file:///site/b.css");
        Map<URI, String> sheets = Map.of(
                a, "@import 'a.css'; @import 'b.css'; @import url(./a.css); p { color: red }",
                b, "@import 'a.css'; @import 'b.css'; div { color: blue }");
        AtomicInteger fetches = new AtomicInteger();
        Stylesheet out = new Stylesheet();
        CssParser.parse("@import 'a.css'; @import 'a.css'; span { color: green }", URI.create("file:///site/page.html"), u -> {
            fetches.incrementAndGet();
            return sheets.get(u);
        }, out);
        assertEquals(3, out.size());
        assertEquals(2, fetches.get());
    }
}
