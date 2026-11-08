package ai.resourcepack.ui.framework.pack;

import ai.resourcepack.common.shader.PackSource;
import ai.resourcepack.ui.framework.Settings;
import ai.resourcepack.ui.framework.render.Palette;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A pack plugin writes its pack after MC GUI has started (Nexo does): served from here, MC GUI merges it in while
 * running, once two looks agree on it, and not in external mode, where the pack is somebody else's to send.
 */
class RemergeTest {
    private static final Palette PALETTE = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));

    private static Settings settings(String mode) {
        YamlConfiguration c = new YamlConfiguration();
        c.set("resource-pack.mode", mode);
        return Settings.load(c);
    }

    private static ForeignShaders.Found hudEngine() throws Exception {
        Path root = Path.of(RemergeTest.class.getResource("/foreign-shaders/hudengine/pack.mcmeta").toURI()).getParent();
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                files.put(root.relativize(f).toString().replace('\\', '/'), Files.readAllBytes(f));
            }
        }
        return new ForeignShaders.Found("HUDEngine", "HUDEngine/pack", PackSource.ofBytes("HUDEngine", files));
    }

    @Test
    void aPackWrittenAfterTheStartIsMergedInWhileRunning(@TempDir Path dir) throws Exception {
        ResourcePack pack = new ResourcePack(settings("serve"), PALETTE, dir, Logger.getLogger("test"), List.of());
        byte[] before = pack.bytes;
        String sha = pack.sha1;
        ForeignShaders.Found hud = hudEngine();

        assertFalse(pack.remerge(List.of()), "nothing new");
        assertFalse(pack.remerge(List.of(hud)), "seen once: it may be half written");
        assertTrue(pack.remerge(List.of(hud)), "seen twice: merged");
        assertNotEquals(sha, pack.sha1);
        assertEquals("HUDEngine", pack.mergedWith.plugin());
        assertTrue(ForeignShaders.unzip(pack.bytes).keySet().stream().anyMatch(f -> f.startsWith(ForeignShaders.DIRECTORY)));
        assertArrayEquals(pack.bytes, Files.readAllBytes(dir.resolve("pack.zip")));
        assertFalse(pack.remerge(List.of(hud)), "the same again: nothing to do");

        // Gone again: back to ours alone.
        assertFalse(pack.remerge(List.of()));
        assertTrue(pack.remerge(List.of()));
        assertArrayEquals(before, pack.bytes);
        assertEquals(sha, pack.sha1);
    }

    @Test
    void notInExternalMode(@TempDir Path dir) throws Exception {
        ResourcePack pack = new ResourcePack(settings("external"), PALETTE, dir, Logger.getLogger("test"), List.of());
        assertFalse(pack.remergesLive());
        ForeignShaders.Found hud = hudEngine();
        assertFalse(pack.remerge(List.of(hud)));
        assertFalse(pack.remerge(List.of(hud)));
    }
}
