package ai.resourcepack.ui;

import ai.resourcepack.ui.html.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class PageStoreTest {
    private static PageStore store(Path dir) {
        return new PageStore(dir, new Page.Loader(false, null, false, dir), Logger.getLogger("test"));
    }

    private static void write(Path file, String title, long stamp) throws IOException {
        Files.writeString(file, "<html><head><title>" + title + "</title></head><body></body></html>");
        Files.setLastModifiedTime(file, FileTime.fromMillis(stamp));
    }

    @Test
    void oneLoadForManyAsks(@TempDir Path dir) throws Exception {
        write(dir.resolve("menu.html"), "one", 1_000_000);
        PageStore pages = store(dir);
        Page a = pages.load("menu").get(30, TimeUnit.SECONDS), b = pages.load("/menu.html").get(30, TimeUnit.SECONDS);
        assertSame(a, b);
        assertSame(a, pages.loaded("menu"));
        assertNull(pages.load("missing").get(30, TimeUnit.SECONDS));
        pages.close();
    }

    @Test
    void aFailedReloadKeepsTheOldPageAndTriesAgain(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("menu.html");
        write(file, "one", 1_000_000);
        PageStore pages = store(dir);
        List<String> reloaded = new ArrayList<>();
        pages.onReload = reloaded::add;
        Page first = pages.load("menu").get(30, TimeUnit.SECONDS);

        // An editor moving the file aside to save it: the page is missing for a moment.
        Files.delete(file);
        pages.checkForChanges();
        assertSame(first, pages.loaded("menu"));
        assertEquals(List.of(), reloaded);

        write(file, "two", 2_000_000);
        pages.checkForChanges();
        assertEquals("two", pages.loaded("menu").title());
        assertEquals(List.of("menu"), reloaded);
        pages.close();
    }

    @Test
    void aFileReadHalfWrittenIsReadAgainEvenIfItsStampDoesNotMove(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("menu.html");
        write(file, "one", 1_000_000);
        PageStore pages = store(dir);
        Page first = pages.load("menu").get(30, TimeUnit.SECONDS);

        // Not UTF-8: Files.readString fails, as on a file caught mid-write.
        Files.write(file, new byte[]{(byte) 0xC3, (byte) 0x28});
        Files.setLastModifiedTime(file, FileTime.fromMillis(2_000_000));
        pages.checkForChanges();
        assertSame(first, pages.loaded("menu"));

        // Finished within the same timestamp: only the retry picks it up.
        write(file, "two", 2_000_000);
        pages.checkForChanges();
        assertEquals("two", pages.loaded("menu").title());
        pages.close();
    }

    /** Windows-1252 "café": the é one byte that is never UTF-8, however long the file is left alone. */
    private static void writeLatin1(Path file, long stamp) throws IOException {
        Files.write(file, "<html><head><title>caf\u00e9</title></head><body></body></html>".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        Files.setLastModifiedTime(file, FileTime.fromMillis(stamp));
    }

    @Test
    void aReloadStillNotUtf8WhenReadAgainIsShownWithReplacementCharacters(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("menu.html");
        write(file, "one", 1_000_000);
        PageStore pages = store(dir);
        Page first = pages.load("menu").get(30, TimeUnit.SECONDS);

        writeLatin1(file, 2_000_000);
        pages.checkForChanges();
        assertSame(first, pages.loaded("menu"));
        pages.checkForChanges();
        assertEquals("caf\uFFFD", pages.loaded("menu").title());
        pages.close();
    }

    @Test
    void aPageNotUtf8FromTheStartStillLoads(@TempDir Path dir) throws Exception {
        writeLatin1(dir.resolve("menu.html"), 1_000_000);
        PageStore pages = store(dir);
        assertEquals("caf\uFFFD", pages.load("menu").get(30, TimeUnit.SECONDS).title());
        pages.close();
    }
}
