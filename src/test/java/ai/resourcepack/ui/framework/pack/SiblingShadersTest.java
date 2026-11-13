package ai.resourcepack.ui.framework.pack;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which sibling packs replace any of our files with a copy that is not ours, for some client version. */
class SiblingShadersTest {
    private static final Map<String, String> OUR_PACK = TestPacks.ours();
    private static final Map<String, byte[]> OUR_FILES = TestPacks.bytes(OUR_PACK);
    private static final JsonArray OUR_OVERLAYS = MergeCheck.overlays(MergeCheck.meta(OUR_FILES));
    private static final Set<String> OURS;

    static {
        try {
            OURS = SiblingShaders.minecraftFiles(new ZipInputStream(new ByteArrayInputStream(TestPacks.zip(OUR_PACK))));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Read front to back and by its central directory (as a fetched pack is), which must agree. */
    private static boolean replaces(Map<String, String> sibling) throws IOException {
        byte[] zip = TestPacks.zip(sibling);
        boolean streamed = SiblingShaders.replaces(OURS, OUR_FILES, OUR_OVERLAYS, new ZipInputStream(new ByteArrayInputStream(zip)));
        Path tmp = Files.createTempFile("sibling", ".zip");
        try {
            Files.write(tmp, zip);
            try (ZipFile file = new ZipFile(tmp.toFile())) {
                assertEquals(streamed, SiblingShaders.replaces(OURS, OUR_FILES, OUR_OVERLAYS, file), "read by its central directory");
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return streamed;
    }

    /** A sibling's pack of nothing but [overlay] (listed for every format) holding [files]. */
    private static Map<String, String> sibling(String overlay, Map<String, String> files) {
        return TestPacks.withOverlay(Map.of("pack.mcmeta", "{\"pack\":{\"min_format\":75,\"max_format\":97},\"overlays\":{\"entries\":[]}}"),
                overlay, files);
    }

    @Test
    void ourFilesAreReadFromTheRootAndEveryOverlay() throws IOException {
        Set<String> ours = SiblingShaders.minecraftFiles(new ZipInputStream(new ByteArrayInputStream(TestPacks.zip(Map.of(
                "pack.mcmeta", "", "assets/minecraft/shaders/core/text.vsh", "",
                "uiengine_1_21_11/assets/minecraft/shaders/core/rendertype_text.vsh", "", "assets/uiengine/font/pointer.json", "")))));
        assertEquals(Set.of("assets/minecraft/shaders/core/text.vsh", "assets/minecraft/shaders/core/rendertype_text.vsh"), ours);
    }

    @Test
    void dialogEnginesDrawnPackReplacesOurs() throws IOException {
        // Its text shader is in an overlay (rpai_head_v*).
        assertTrue(replaces(sibling("rpai_head_v1", Map.of("assets/minecraft/shaders/core/rendertype_text.vsh", "void main() {}",
                "assets/dialogengine/textures/a.png", "x"))));
    }

    @Test
    void aPackOfTexturesAndOtherShadersDoesNot() throws IOException {
        assertFalse(replaces(sibling("over", Map.of("assets/minecraft/textures/block/stone.png", "x",
                "assets/minecraft/shaders/core/rendertype_entity_cutout.vsh", "void main() {}", "assets/minecraft/shaders/core/gui.vsh", "x",
                "assets/rpengine/shaders/core/rendertype_text.vsh", "x"))));
    }

    @Test
    void aPackWithItsOwnCodeMergedIntoOursDoesNot() throws IOException {
        // An RP Engine bundle carrying a plugin's shaders built on MC GUI's 26.3 ones.
        Map<String, String> files = new LinkedHashMap<>();
        files.put("assets/minecraft/shaders/core/text.vsh",
                TestPacks.renamedMerge(Shaders.textVsh(Shaders.Dialect.V26_3, TestPacks.PALETTE), TestPacks.BALL_ARROW, "pb_main"));
        files.put("assets/minecraft/shaders/core/position_tex_color.vsh",
                TestPacks.renamedMerge(Shaders.positionTexColorVsh(Shaders.Dialect.V26_3), TestPacks.BALL_ARROW, "pb_main"));
        Map<String, String> pack = new LinkedHashMap<>();
        pack.put("pack.mcmeta", "{\"pack\":{\"min_format\":97,\"max_format\":97},\"overlays\":{\"entries\":[]}}");
        files.forEach((k, v) -> pack.put(k, v));
        // Its text.vsh at the root is read by every version from 26.2, whose own text.vsh it is not: only 26.3 counts.
        assertTrue(replaces(pack), "26.2 reads the 26.3 text.vsh at its root");
        Map<String, String> only263 = TestPacks.withOverlay(Map.of("pack.mcmeta", pack.get("pack.mcmeta")), "pb_26_3", files);
        only263.put("pack.mcmeta", only263.get("pack.mcmeta").replace("\"min_format\":75,\"max_format\":97,\"formats\":[75,97]",
                "\"min_format\":97,\"max_format\":97"));
        assertFalse(replaces(only263));
        // The same, merged from an MC GUI that has changed since.
        only263.put("pb_26_3/assets/minecraft/shaders/core/text.vsh", only263.get("pb_26_3/assets/minecraft/shaders/core/text.vsh")
                .replace("mcbPointer = 1;", "mcbPointer = 3;"));
        assertTrue(replaces(only263));
    }
}
