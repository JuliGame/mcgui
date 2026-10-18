package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.Selector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class GridRepeatTest {
    @Test
    void hugeRepeatCountsLayOutQuickly(@TempDir Path dir) {
        for (String columns : new String[]{"repeat(100000, 1fr)", "repeat(auto-fill, minmax(0.001px, 1fr))"}) {
            Page page = Page.of("<div style='display: grid; grid-template-columns: " + columns + "'><p id=a style='margin: 0'>a</p><p>b</p></div>",
                    dir.resolve("grid.html"), new Page.Loader(false, dir));
            HtmlRenderer.Frame f = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> HtmlRenderer.render(page, 1024, 576, 1, 0, Selector.State.NONE), columns);
            assertEquals("a", f.elementAt(9, 12).id(), columns);
        }
    }
}
