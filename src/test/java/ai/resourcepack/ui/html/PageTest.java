package ai.resourcepack.ui.html;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageTest {
    private static final Page.Loader OFFLINE = new Page.Loader(false, null);

    private static Page page(String css) {
        return Page.of("<html><head><style>" + css + "</style></head><body></body></html>", Path.of("test.html").toAbsolutePath(), OFFLINE);
    }

    @Test
    void dataUriKeepsStrayPercentAndPlus() {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='100%' height='50%'><path d='M0 0+1'/></svg>";
        byte[] bytes = Page.dataUri("data:image/svg+xml;utf8," + svg);
        assertEquals(svg, new String(bytes, StandardCharsets.UTF_8));
    }

    @Test
    void dataUriDecodesValidEscapes() {
        assertEquals("<svg a=\"1\">é", new String(Page.dataUri("data:image/svg+xml,%3Csvg%20a=%221%22%3E%C3%A9"), StandardCharsets.UTF_8));
    }

    @Test
    void base64DataUriWithEscapesAndBrokenPadding() {
        assertArrayEquals("hi?".getBytes(StandardCharsets.UTF_8), Page.dataUri("data:text/plain;BASE64,aGk%2F"));
        assertNull(Page.dataUri("data:image/png;base64,a"));
    }

    @Test
    void brokenDataImageIsNullNotAnException() {
        Page p = page("");
        String uri = "data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='100%' height='100%'><rect width='10' height='10'/></svg>";
        assertNotNull(p.image(uri));
        assertNull(p.image("data:image/png;base64,a"));
        assertNull(p.image("data:image/png;base64,a"));
    }

    @Test
    void siblingStatesSeesSiblingCombinatorsOnlyInStateRules() {
        assertTrue(page(".tab:hover + .panel { color: red }").siblingStates());
        assertTrue(page("@media (min-width: 1px) { .a:active ~ .b { color: red } }").siblingStates());
        assertTrue(page(":is(.a:hover ~ .b) { color: red }").siblingStates());
        assertFalse(page(".a + .b { color: red } .a:hover { color: blue }").siblingStates());
        assertFalse(page("li:nth-child(2n+1):hover, [class~=x]:hover { width: calc(1px + 2px) }").siblingStates());
        assertFalse(page("/* .a:hover + .b { } */ .a:hover { color: red }").siblingStates());
    }

    @Test
    void unclosedCommentsAndBracketsDoNotTakeQuadraticTime() {
        // Each of these took tens of seconds per 100 KB with the regexes this scan replaced.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertFalse(page("/* ".repeat(100_000)).siblingStates());
            assertFalse(page("a:hover" + "[".repeat(300_000) + "{}").siblingStates());
        });
    }
}
