package ai.resourcepack.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A second /ui import of a project removes what it no longer has, and nothing anybody changed or owns. */
class PageImportTidyTest {
    @TempDir
    Path dir;

    private Map<String, byte[]> files(String... namesAndContent) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (int i = 0; i < namesAndContent.length; i += 2) out.put(namesAndContent[i], namesAndContent[i + 1].getBytes(StandardCharsets.UTF_8));
        return out;
    }

    /** What /ui import does: write the files, then tidy. */
    private List<String>[] importProject(String project, Map<String, byte[]> files) throws Exception {
        Path pages = dir.resolve("pages");
        Files.createDirectories(pages);
        for (Map.Entry<String, byte[]> f : files.entrySet()) Files.write(pages.resolve(f.getKey()), f.getValue());
        List<String> removed = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        PageImport.tidy(dir.resolve("imports.json"), pages, project, files, removed, kept);
        @SuppressWarnings("unchecked") List<String>[] out = new List[] {removed, kept};
        return out;
    }

    @Test
    void removesWhatTheProjectNoLongerHas() throws Exception {
        importProject("abc", files("menu.html", "a", "shop.html", "b", "style.css", "c"));
        List<String>[] r = importProject("abc", files("menu.html", "a2", "store.html", "b"));
        assertEquals(List.of("shop.html", "style.css"), r[0].stream().sorted().toList());
        assertTrue(r[1].isEmpty());
        assertFalse(Files.exists(dir.resolve("pages/shop.html")));
        assertTrue(Files.exists(dir.resolve("pages/store.html")));
        assertTrue(Files.exists(dir.resolve("pages/menu.html")));
    }

    @Test
    void keepsAFileChangedOnTheServer() throws Exception {
        importProject("abc", files("menu.html", "a", "shop.html", "b"));
        Files.writeString(dir.resolve("pages/shop.html"), "edited here");
        List<String>[] r = importProject("abc", files("menu.html", "a"));
        assertTrue(r[0].isEmpty());
        assertEquals(List.of("shop.html"), r[1]);
        assertTrue(Files.exists(dir.resolve("pages/shop.html")));
    }

    @Test
    void leavesAnotherProjectsFilesAndUntrackedOnes() throws Exception {
        Files.createDirectories(dir.resolve("pages"));
        Files.writeString(dir.resolve("pages/handmade.html"), "mine");
        importProject("abc", files("menu.html", "a", "shared.css", "s"));
        importProject("xyz", files("rules.html", "r", "shared.css", "s"));
        List<String>[] r = importProject("abc", files("menu.html", "a"));
        assertTrue(r[0].isEmpty(), "shared.css belongs to xyz's import too");
        assertTrue(Files.exists(dir.resolve("pages/shared.css")));
        assertTrue(Files.exists(dir.resolve("pages/rules.html")));
        assertTrue(Files.exists(dir.resolve("pages/handmade.html")));
    }

    @Test
    void aFirstImportRemovesNothing() throws Exception {
        Files.createDirectories(dir.resolve("pages"));
        Files.writeString(dir.resolve("pages/old.html"), "x");
        List<String>[] r = importProject("abc", files("menu.html", "a"));
        assertTrue(r[0].isEmpty() && r[1].isEmpty());
        assertTrue(Files.exists(dir.resolve("pages/old.html")));
    }
}
