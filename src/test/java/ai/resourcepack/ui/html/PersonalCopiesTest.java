package ai.resourcepack.ui.html;

import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A personal page's copy ({@link Personal.Copy}) is found by its document for as long as something holds it, and lets
 * go of its document once nothing does: a copy refers to its document, so one kept by a map keyed weakly by that
 * document kept every copy ever made (a player's page refilled every few seconds, each with its last picture measured).
 */
class PersonalCopiesTest {
    private static final Page.Loader OFFLINE = new Page.Loader(false, null);

    private static Page page() {
        return Page.of("<html><head><style>body{margin:0;background:#fff}p{margin:8px;width:120px;height:20px}p:hover{color:red}</style></head><body><p>Hello, Steve</p><p>Rest</p></body></html>",
                Path.of("copies.html").toAbsolutePath(), OFFLINE);
    }

    private static Personal.Copy copyOf(Page page, Document d) {
        return Personal.of(d, page, "t", Set.of(d.select("p").first()), Map.of());
    }

    /** Collects until [ref] is cleared, or gives up: whatever still holds its object holds it for good. */
    private static void collect(WeakReference<?> ref) {
        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void aCopyNobodyHoldsLetsGoOfItsDocument() {
        Page page = page();
        Document d = page.document.clone();
        copyOf(page, d);
        WeakReference<Document> doc = new WeakReference<>(d);
        d = null;
        collect(doc);
        assertNull(doc.get(), "a copy nothing holds kept its document");
    }

    @Test
    void aHeldCopyIsFoundByItsDocumentAndItsMeasuredPictureIsNotKept() {
        Page page = page();
        Document d = page.document.clone();
        Personal.Copy held = copyOf(page, d);
        HtmlRenderer.Frame f = HtmlRenderer.render(page, d, 1024, 512, 1, 0, HtmlRenderer.state(null, null));
        // Measures [f]: where the player's own values paint in it.
        var rest = d.select("p").last();
        Repaint.Prepared p = Repaint.prepare(page, d, 1024, 512, 1, 0, HtmlRenderer.state(rest, null), f, Set.of(rest), RenderBudget.of(10_000));
        assertNotNull(p, "hovering the second line is a repaint");
        Personal.clear(held, f, p);
        WeakReference<java.awt.image.BufferedImage> image = new WeakReference<>(f.image());
        f = null;
        p = null;
        collect(image);
        assertNull(image.get(), "the copy kept the last picture it measured");
        assertSame(held, Personal.copy(d), "a copy something holds is found by its document");
    }
}
