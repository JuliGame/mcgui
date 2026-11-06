package ai.resourcepack.ui.framework.pack;

import ai.resourcepack.common.shader.PackSource;
import ai.resourcepack.ui.framework.render.Palette;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Another plugin's text shader (HUDEngine's, as it ships it: src/test/resources/foreign-shaders/hudengine) is found on
 * disk once however many copies there are, and merged into ours for every version MC GUI supports, each merged file
 * running their code and then ours. Whether the merged shaders compile and draw is checked on a GPU and in a client
 * (NOTES.md); this writes them to build/merge-dump/ for that.
 */
class ForeignShadersTest {
    private static final Palette PALETTE = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));
    private static final Logger LOG = Logger.getLogger("test");

    private static Path fixture() throws URISyntaxException {
        return Path.of(ForeignShadersTest.class.getResource("/foreign-shaders/hudengine/pack.mcmeta").toURI()).getParent();
    }

    private static Map<String, byte[]> files(Path root) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                out.put(root.relativize(f).toString().replace('\\', '/'), Files.readAllBytes(f));
            }
        }
        return out;
    }

    private static byte[] ours(Path dir) throws IOException {
        Path sounds = Files.createDirectories(dir.resolve("sounds"));
        return ResourcePack.build(PALETTE, sounds, LOG);
    }

    @Test
    void onePluginsShaderIsFoundOnceWhereverItLies(@TempDir Path plugins) throws Exception {
        // HUDEngine's own folder, a copy in Nexo's external_packs, and Nexo's output with ours merged in beside theirs.
        Path hud = Files.createDirectories(plugins.resolve("HUDEngine").resolve("pack"));
        for (Map.Entry<String, byte[]> f : files(fixture()).entrySet()) {
            Path to = hud.resolve(f.getKey());
            Files.createDirectories(to.getParent());
            Files.write(to, f.getValue());
        }
        Path nexo = Files.createDirectories(plugins.resolve("Nexo").resolve("pack").resolve("external_packs"));
        Files.write(nexo.resolve("HUDEngine.zip"), ForeignShaders.zip(files(fixture())));
        Map<String, byte[]> output = new LinkedHashMap<>(ForeignShaders.unzip(ours(plugins.resolve("tmp"))));
        output.putAll(files(fixture()));
        Files.write(plugins.resolve("Nexo").resolve("pack").resolve("pack.zip"), ForeignShaders.zip(output));
        Path mcgui = Files.createDirectories(plugins.resolve("MCGUI"));

        List<ForeignShaders.Found> found = ForeignShaders.scan(ForeignShaders.folders(plugins, mcgui, n -> true), plugins);
        assertEquals(1, found.size(), found.toString());
        assertTrue(found.get(0).pack().hasTextShader());
    }

    @Test
    void ourOwnPackIsNotAnotherPlugins(@TempDir Path dir) throws Exception {
        PackSource ours = PackSource.ofBytes("MC GUI", ForeignShaders.unzip(ours(dir)));
        assertFalse(ForeignShaders.withoutOurs(ours).hasTextShader());
    }

    @Test
    void hudEnginesShaderMergesWithOursOnEveryVersion(@TempDir Path dir) throws Exception {
        byte[] zip = ours(dir);
        ForeignShaders.Found hud = new ForeignShaders.Found("HUDEngine", "HUDEngine/pack", PackSource.ofBytes("HUDEngine", files(fixture())));
        ForeignShaders.Result r = ForeignShaders.merge(zip, hud, Shaders.Dialect.V1_21_11.minFormat);
        assertEquals(List.of(), r.refused());
        assertEquals("1.21.11, 26.1, 26.2, 26.3", String.join(", ", r.merged()));
        PackSource merged = PackSource.ofBytes("merged", ForeignShaders.unzip(r.zip()));
        Path dump = Path.of("build", "merge-dump");
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            String name = d.textShader();
            String vsh = merged.resolve("assets/minecraft/shaders/core/" + name + ".vsh", d.minFormat).orElseThrow();
            String fsh = merged.resolve("assets/minecraft/shaders/core/" + name + ".fsh", d.minFormat).orElseThrow();
            // Theirs runs, then ours.
            assertTrue(vsh.contains("HEIGHT_BIT") && vsh.contains("void " + Shaders.VERTEX_BASE + "()"), d + ": " + vsh);
            assertTrue(vsh.indexOf("void " + Shaders.VERTEX_BASE + "()") < vsh.lastIndexOf("void main()"), d.name());
            assertEquals(Shaders.textFsh(d, PALETTE), fsh, d + ": the fragment shader is ours, as theirs has none");
            Files.createDirectories(dump.resolve(d.name()));
            Files.writeString(dump.resolve(d.name()).resolve("merged.vsh"), vsh, StandardCharsets.UTF_8);
        }
        // Every entry of ours stands, with the merged ones listed last.
        String meta = new String(ForeignShaders.unzip(r.zip()).get("pack.mcmeta"), StandardCharsets.UTF_8);
        assertTrue(meta.contains("uiengine_none") && meta.lastIndexOf(ForeignShaders.DIRECTORY) > meta.lastIndexOf("uiengine_26_3"), meta);
    }

    /**
     * BetterHud 2.2.0's real generated pack (src/test/resources/foreign-shaders/betterhud-2.2.0, MIT): it replaces both
     * halves of the text shader, so both are merged, theirs running first in each.
     */
    @Test
    void betterHudsShadersMergeWithOursOnEveryVersion(@TempDir Path dir) throws Exception {
        Path root = Path.of(ForeignShadersTest.class.getResource("/foreign-shaders/betterhud-2.2.0/pack.mcmeta").toURI()).getParent();
        ForeignShaders.Found bh = new ForeignShaders.Found("BetterHud", "BetterHud/build", PackSource.ofBytes("BetterHud", files(root)));
        ForeignShaders.Result r = ForeignShaders.merge(ours(dir), bh, Shaders.Dialect.V1_21_11.minFormat);
        assertEquals(List.of(), r.refused());
        assertEquals("1.21.11, 26.1, 26.2, 26.3", String.join(", ", r.merged()));
        PackSource merged = PackSource.ofBytes("merged", ForeignShaders.unzip(r.zip()));
        Path dump = Path.of("build", "merge-dump-betterhud");
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            String name = d.textShader();
            String vsh = merged.resolve("assets/minecraft/shaders/core/" + name + ".vsh", d.minFormat).orElseThrow();
            String fsh = merged.resolve("assets/minecraft/shaders/core/" + name + ".fsh", d.minFormat).orElseThrow();
            assertTrue(vsh.contains("void " + Shaders.VERTEX_BASE + "()") && fsh.contains("void " + Shaders.FRAGMENT_BASE + "()"), d.name());
            Files.createDirectories(dump.resolve(d.name()));
            Files.writeString(dump.resolve(d.name()).resolve("text.vsh"), vsh, StandardCharsets.UTF_8);
            Files.writeString(dump.resolve(d.name()).resolve("text.fsh"), fsh, StandardCharsets.UTF_8);
        }
        // Every file the merged shaders import that BetterHud's pack carries is in our merged overlay too (its 1.21.11
        // sample_lightmap.glsl, which that game lacks), so a player without BetterHud's pack still compiles them.
        Map<String, byte[]> out = ForeignShaders.unzip(r.zip());
        int copied = 0;
        for (String f : out.keySet()) {
            if (f.startsWith(ForeignShaders.DIRECTORY) && f.contains("/shaders/include/")) copied++;
        }
        boolean importsLightmap = merged.resolve("assets/minecraft/shaders/core/rendertype_text.vsh", Shaders.Dialect.V1_21_11.minFormat)
                .orElseThrow().contains("sample_lightmap.glsl");
        if (importsLightmap) {
            assertTrue(out.containsKey(ForeignShaders.DIRECTORY + Shaders.Dialect.V1_21_11.minFormat + "/assets/minecraft/shaders/include/sample_lightmap.glsl"),
                    "includes copied: " + copied);
        }
    }

    /** A foreign shader in 26.2's form (no layout(location), as ItemsAdder lists for 26.3 too) is not merged for 26.3. */
    @Test
    void anOlderFormShaderIsNotMergedFor263() {
        String old = "#version 330\nin vec3 Position;\nout vec4 vertexColor;\nvoid main() {}\n";
        String placed = "#version 330\nlayout(location = 0) in vec3 Position;\nlayout(location = 1) out vec4 vertexColor;\nvoid main() {}\n";
        int f263 = Shaders.Dialect.V26_3.minFormat, f262 = Shaders.Dialect.V26_2.minFormat;
        String path = "assets/minecraft/shaders/core/text.vsh";
        assertTrue(ForeignShaders.unusable(new ai.resourcepack.common.shader.MergedShaders.Outcome(f263, f263, "26.3", Map.of(path, old), null)) != null);
        assertEquals(null, ForeignShaders.unusable(new ai.resourcepack.common.shader.MergedShaders.Outcome(f263, f263, "26.3", Map.of(path, placed), null)));
        assertEquals(null, ForeignShaders.unusable(new ai.resourcepack.common.shader.MergedShaders.Outcome(f262, f262 + 8, "26.2", Map.of(path, old), null)));
    }
}
