package ai.resourcepack.ui.framework.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pack's overlay entries against the game's rules (checked by hand against the 1.21.11 server jar's own
 * PackMetadataSection and OverlayMetadataSection codecs).
 */
class MergeCheckTest {
    /** ItemsAdder's overlays as a real ItemsAdder 4.0 wrote them (PluginBugs/Issues-ItemsAdder#5185). */
    private static final String ITEMSADDER = """
            [{"directory":"ia_overlay_1_20_5_plus","formats":[32,9999],"min_format":32,"max_format":9999},
             {"directory":"ia_overlay_1_21_4_to_5","formats":[46,55]},
             {"directory":"ia_overlay_1_21_6_plus","formats":[63,9999],"min_format":63,"max_format":9999}]""";

    private static JsonArray ours() {
        return JsonParser.parseString(ResourcePack.mcmeta()).getAsJsonObject().getAsJsonObject("overlays").getAsJsonArray("entries");
    }

    private static JsonArray merged(JsonArray theirs, JsonArray ours) {
        JsonArray all = theirs.deepCopy();
        all.addAll(ours);
        return all;
    }

    @Test
    void ourOverlaysAreValidAloneAndMergedIntoAPackWithOldRanges() {
        assertNull(MergeCheck.invalidOverlays(ours()));
        assertNull(MergeCheck.invalidOverlays(merged(JsonParser.parseString(ITEMSADDER).getAsJsonArray(), ours())));
    }

    @Test
    void overlaysWithoutFormatsBreakAPackWithOldRanges() {
        // The pack before 0.6.7: min_format/max_format only. Alone it is fine; next to ItemsAdder's the game refuses
        // every overlay, and the pack with them.
        JsonArray before = JsonParser.parseString("""
                [{"directory": "uiengine_1_21_11", "min_format": 75, "max_format": 83},
                 {"directory": "uiengine_26_1", "min_format": 84, "max_format": 87},
                 {"directory": "uiengine_26_2", "min_format": 88, "max_format": 96}]""").getAsJsonArray();
        assertNull(MergeCheck.invalidOverlays(before));
        String why = MergeCheck.invalidOverlays(merged(JsonParser.parseString(ITEMSADDER).getAsJsonArray(), before));
        assertNotNull(why);
        assertTrue(why.contains("uiengine_1_21_11"), why);
    }

    @Test
    void formatsAboveSixtyFourAloneAreRefused() {
        JsonArray formatsOnly = JsonParser.parseString("""
                [{"directory": "uiengine_26_2", "min_format": 88, "max_format": 96, "formats": [88, 96]}]""").getAsJsonArray();
        assertNotNull(MergeCheck.invalidOverlays(formatsOnly));
    }

    @Test
    void eachVersionGetsItsOwnOverlay() {
        JsonArray o = ours();
        int[][] formats = {{75, 0}, {84, 0}, {88, 0}, {97, 1}};
        String[] dirs = {"uiengine_1_21_11", "uiengine_26_1", "uiengine_26_2", "uiengine_26_3"};
        for (int v = 0; v < formats.length; v++) {
            String last = null;
            for (var e : o) {
                JsonObject entry = e.getAsJsonObject();
                if (MergeCheck.applies(entry, formats[v])) last = entry.get("directory").getAsString();
            }
            assertEquals(dirs[v], last);
        }
        for (var e : o) assertFalse(MergeCheck.applies(e.getAsJsonObject(), new int[]{74, 0}) && !e.getAsJsonObject().get("directory").getAsString().equals("uiengine_none"));
    }

    /** A MergeCheck over [plugins], with a pack of ours that is only its pack.mcmeta, whose log lines go to [said]. */
    private static MergeCheck check(java.nio.file.Path plugins, java.util.function.Predicate<String> installed, java.util.List<java.util.logging.LogRecord> said) throws java.io.IOException {
        java.io.ByteArrayOutputStream zip = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(zip)) {
            out.putNextEntry(new java.util.zip.ZipEntry("pack.mcmeta"));
            out.write(ResourcePack.mcmeta().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        java.util.logging.Logger logger = java.util.logging.Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { said.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        return new MergeCheck(plugins, zip.toByteArray(), logger, installed);
    }

    @Test
    void aPackPluginThisServerDoesNotHaveIsNotRead(@org.junit.jupiter.api.io.TempDir java.nio.file.Path plugins) throws Exception {
        // Oraxen's pack left on disk after a move to Nexo.
        java.nio.file.Path zip = plugins.resolve(MergeCheck.OUTPUTS.get("Oraxen"));
        java.nio.file.Files.createDirectories(zip.getParent());
        java.nio.file.Files.write(zip, new byte[]{1, 2, 3});
        java.util.List<java.util.logging.LogRecord> said = new java.util.ArrayList<>();
        MergeCheck nexoOnly = check(plugins, name -> name.equals("Nexo"), said);
        nexoOnly.run();
        nexoOnly.run();
        assertTrue(said.isEmpty(), () -> said.get(0).getMessage());
        MergeCheck withOraxen = check(plugins, name -> name.equals("Oraxen"), said);
        withOraxen.run();
        withOraxen.run();
        assertFalse(said.isEmpty());
    }

    @Test
    void aPackThatCannotBeReadIsSaidOnlyWhenTheSameFileFailsTwice(@org.junit.jupiter.api.io.TempDir java.nio.file.Path plugins) throws Exception {
        java.nio.file.Path zip = plugins.resolve(MergeCheck.OUTPUTS.get("Nexo"));
        java.nio.file.Files.createDirectories(zip.getParent());
        java.nio.file.Files.write(zip, new byte[]{1, 2, 3});
        java.util.List<java.util.logging.LogRecord> said = new java.util.ArrayList<>();
        MergeCheck check = check(plugins, name -> true, said);
        check.run();
        assertTrue(said.isEmpty(), "a pack still being written is not a broken merge");
        check.run();
        assertEquals(1, said.size());
        assertEquals(java.util.logging.Level.WARNING, said.get(0).getLevel());
        assertTrue(said.get(0).getMessage().contains("could not be read"), said.get(0).getMessage());
        check.run();
        assertEquals(1, said.size(), "said once");
    }

    @Test
    void aMergedDialogEnginePackIsToldToComeOut(@org.junit.jupiter.api.io.TempDir java.nio.file.Path plugins) throws Exception {
        // ItemsAdder's output with MC GUI's overlays and Dialog Engine's drawn-dialog overlay in one pack.mcmeta.
        JsonObject meta = JsonParser.parseString(ResourcePack.mcmeta()).getAsJsonObject();
        meta.getAsJsonObject("overlays").getAsJsonArray("entries").add(JsonParser.parseString(
                "{\"directory\":\"rpai_dialog_v75\",\"min_format\":75,\"max_format\":88,\"formats\":{\"min_inclusive\":75,\"max_inclusive\":88}}"));
        java.nio.file.Path zip = plugins.resolve(MergeCheck.OUTPUTS.get("ItemsAdder"));
        java.nio.file.Files.createDirectories(zip.getParent());
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(zip))) {
            out.putNextEntry(new java.util.zip.ZipEntry("pack.mcmeta"));
            out.write(meta.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        java.util.List<java.util.logging.LogRecord> said = new java.util.ArrayList<>();
        check(plugins, name -> name.equals("ItemsAdder"), said).run();
        assertEquals(1, said.size());
        String message = said.get(0).getMessage();
        assertTrue(message.contains("rpai_dialog_*"), message);
        assertTrue(message.contains("DialogEngine/drawn-pack"), message);
    }

    /** RP Engine's bundle as Pufferball builds it: our pack, plus per version an overlay listed last with its shaders. */
    private static java.util.List<java.util.logging.LogRecord> mergedWithTheirShaders(java.nio.file.Path plugins,
            java.util.function.UnaryOperator<String> textOf262) throws Exception {
        java.util.Map<String, String> pack = TestPacks.ours();
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            String dir = "pb_" + d.name().toLowerCase(java.util.Locale.ROOT);
            java.util.Map<String, String> theirs = new java.util.LinkedHashMap<>();
            String text = TestPacks.renamedMerge(Shaders.textVsh(d, TestPacks.PALETTE), TestPacks.BALL_ARROW, "pb_main");
            theirs.put("assets/minecraft/shaders/core/" + d.textShader() + ".vsh", d == Shaders.Dialect.V26_2 ? textOf262.apply(text) : text);
            theirs.put("assets/minecraft/shaders/core/position_tex_color.vsh",
                    TestPacks.renamedMerge(Shaders.positionTexColorVsh(d), TestPacks.BALL_ARROW, "pb_main"));
            pack = TestPacks.withOverlay(pack, dir, theirs);
            pack.put("pack.mcmeta", pack.get("pack.mcmeta").replace("\"directory\":\"" + dir + "\",\"min_format\":75,\"max_format\":97,\"formats\":[75,97]",
                    "\"directory\":\"" + dir + "\",\"min_format\":" + d.minFormat + ",\"max_format\":" + d.maxFormat + ",\"formats\":[" + d.minFormat + "," + d.maxFormat + "]"));
        }
        java.nio.file.Path zip = plugins.resolve(MergeCheck.OUTPUTS.get("RPEngine"));
        java.nio.file.Files.createDirectories(zip.getParent());
        java.nio.file.Files.write(zip, TestPacks.zip(pack));
        java.util.List<java.util.logging.LogRecord> said = new java.util.ArrayList<>();
        java.util.logging.Logger logger = java.util.logging.Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            @Override public void publish(java.util.logging.LogRecord r) { said.add(r); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        new MergeCheck(plugins, TestPacks.zip(TestPacks.ours()), logger, name -> name.equals("RPEngine")).run();
        return said;
    }

    @Test
    void aShaderWithAPluginsCodeMergedIntoOursIsOurs(@org.junit.jupiter.api.io.TempDir java.nio.file.Path plugins) throws Exception {
        var said = mergedWithTheirShaders(plugins, s -> s);
        assertEquals(1, said.size());
        assertEquals(java.util.logging.Level.INFO, said.get(0).getLevel(), said.get(0).getMessage());
        String message = said.get(0).getMessage();
        assertTrue(message.contains("merged into MC GUI's position_tex_color.vsh, rendertype_text.vsh, text.vsh"), message);
    }

    @Test
    void aShaderMergedFromAnOlderMcGuiIsSaid(@org.junit.jupiter.api.io.TempDir java.nio.file.Path plugins) throws Exception {
        var said = mergedWithTheirShaders(plugins, s -> s.replace("mcbPointer = 1;", "mcbPointer = 3;"));
        assertEquals(1, said.size());
        String message = said.get(0).getMessage();
        assertEquals(java.util.logging.Level.WARNING, said.get(0).getLevel(), message);
        assertTrue(message.contains("26.2: 1 of MC GUI's files are an older MC GUI's, e.g. text.vsh (it lacks the function mcb_world_vertex()"), message);
        assertFalse(message.contains("26.3:") || message.contains("26.1:"), message);
    }

    @Test
    void aShaderOfAnotherPluginInPlaceOfOursIsAClash(@org.junit.jupiter.api.io.TempDir java.nio.file.Path plugins) throws Exception {
        var said = mergedWithTheirShaders(plugins, s -> "#version 330\nvoid main() {\n    gl_Position = vec4(0.0);\n}\n");
        String message = said.get(0).getMessage();
        assertEquals(java.util.logging.Level.WARNING, said.get(0).getLevel(), message);
        assertTrue(message.contains("26.2: another plugin's copy is used instead of MC GUI's for text.vsh (from pb_v26_2)"), message);
    }
}
