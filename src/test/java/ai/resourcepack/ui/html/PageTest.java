package ai.resourcepack.ui.html;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
