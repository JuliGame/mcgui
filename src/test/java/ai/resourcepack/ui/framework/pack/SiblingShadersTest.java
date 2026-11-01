package ai.resourcepack.ui.framework.pack;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which sibling packs replace any of our files, read off their file names. */
class SiblingShadersTest {
    private static ZipInputStream zip(String... names) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (String n : names) {
                out.putNextEntry(new ZipEntry(n));
                out.write(1);
                out.closeEntry();
            }
        }
        return new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static final Set<String> OURS = Set.of("assets/minecraft/shaders/core/rendertype_text.vsh",
            "assets/minecraft/shaders/core/position_tex_color.fsh", "assets/minecraft/post_effect/blur.json");

    @Test
    void ourFilesAreReadFromTheRootAndEveryOverlay() throws IOException {
        Set<String> ours = SiblingShaders.minecraftFiles(zip("pack.mcmeta", "assets/minecraft/shaders/core/text.vsh",
                "uiengine_1_21_11/assets/minecraft/shaders/core/rendertype_text.vsh", "assets/uiengine/font/pointer.json"));
        assertEquals(Set.of("assets/minecraft/shaders/core/text.vsh", "assets/minecraft/shaders/core/rendertype_text.vsh"), ours);
    }

    @Test
    void dialogEnginesDrawnPackReplacesOurs() throws IOException {
        // Its text shader is in an overlay (rpai_head_v*).
        assertTrue(SiblingShaders.replaces(OURS, zip("pack.mcmeta", "assets/dialogengine/textures/a.png",
                "rpai_head_v1/assets/minecraft/shaders/core/rendertype_text.vsh")));
    }

    @Test
    void aPackOfTexturesAndOtherShadersDoesNot() throws IOException {
        assertFalse(SiblingShaders.replaces(OURS, zip("pack.mcmeta", "assets/minecraft/textures/block/stone.png",
                "assets/minecraft/shaders/core/rendertype_entity_cutout.vsh", "over/assets/minecraft/shaders/core/gui.vsh",
                "assets/rpengine/shaders/core/rendertype_text.vsh")));
    }
}
