package ai.resourcepack.ui.framework.pack;

import ai.resourcepack.ui.framework.render.Palette;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text shaders' colour lookup gives back every symbol, and what text.vsh hands text.fsh lines up. Whether the
 * shaders draw right can only be judged in the real client (NOTES.md).
 */
class ShadersTest {
    private static final Palette PALETTE = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));

    @Test
    void everySymbolColourLooksUpToItsSymbol() {
        SymbolHash hash = new SymbolHash(PALETTE.symbolRgb);
        for (int s = 0; s < Palette.SYMBOLS; s++) assertEquals(s, hash.lookup(PALETTE.symbolRgb[s]), "symbol " + s);
        // The shader reads a slot as four bytes of one uint, lowest first.
        int filled = 0;
        for (int w : hash.packed) for (int b = 0; b < 4; b++) if ((w >>> (b * 8) & 255) != 0) filled++;
        assertEquals(Palette.SYMBOLS, filled);
    }

    @Test
    void shadersCarryTheTableAndNoPlaceholders() {
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            String vsh = Shaders.textVsh(d, PALETTE), fsh = Shaders.textFsh(d, PALETTE);
            for (String s : new String[]{vsh, fsh}) {
                assertFalse(s.contains("@"), "a placeholder was left in a " + d + " shader");
                assertTrue(s.contains("const uint MCB_T[" + SymbolHash.SLOTS / 4 + "]"));
            }
        }
    }

    private static final Pattern VARYING = Pattern.compile("layout\\(location = (\\d+)\\) (flat )?(in|out) (\\w+) (\\w+);");

    @Test
    void everyVaryingTextFshReadsTextVshWritesAtTheSameLocation() {
        String vsh = Shaders.textVsh(Shaders.Dialect.V26_3, PALETTE), fsh = Shaders.textFsh(Shaders.Dialect.V26_3, PALETTE);
        Map<String, String> outs = new HashMap<>();
        Matcher m = VARYING.matcher(vsh);
        while (m.find()) if (m.group(3).equals("out")) outs.put(m.group(5), m.group(1) + " " + m.group(2) + m.group(4));
        m = VARYING.matcher(fsh);
        int ins = 0;
        while (m.find()) {
            if (!m.group(3).equals("in")) continue;
            ins++;
            assertEquals(m.group(1) + " " + m.group(2) + m.group(4), outs.get(m.group(5)), m.group(5));
        }
        assertTrue(ins >= 10, "found " + ins + " inputs");
    }

    /**
     * The overlay chain is JSON the game reads, keeps the game's four outline passes first, only names targets it
     * declares or the game gives it (main and entity_outline: LevelTargetBundle.OUTLINE_TARGETS), keeps its picture
     * from frame to frame, and its own shaders have every placeholder filled.
     */
    @Test
    void overlayChainIsWellFormed() {
        com.google.gson.JsonObject chain = com.google.gson.JsonParser.parseString(Shaders.overlayChain()).getAsJsonObject();
        com.google.gson.JsonObject targets = chain.getAsJsonObject("targets");
        assertTrue(targets.getAsJsonObject("ov_kept").get("persistent").getAsBoolean());
        com.google.gson.JsonArray passes = chain.getAsJsonArray("passes");
        String[] vanilla = {"minecraft:post/entity_sobel", "minecraft:post/entity_outline_box_blur", "minecraft:post/entity_outline_box_blur", "minecraft:post/blit"};
        for (int i = 0; i < vanilla.length; i++) assertEquals(vanilla[i], passes.get(i).getAsJsonObject().get("fragment_shader").getAsString());
        java.util.Set<String> known = new java.util.HashSet<>(targets.keySet());
        known.add("minecraft:main");
        known.add("minecraft:entity_outline");
        java.util.Set<String> shaders = new java.util.HashSet<>();
        for (var p : passes) {
            com.google.gson.JsonObject pass = p.getAsJsonObject();
            assertTrue(known.contains(pass.get("output").getAsString()), pass.toString());
            for (var in : pass.getAsJsonArray("inputs")) assertTrue(known.contains(in.getAsJsonObject().get("target").getAsString()), pass.toString());
            String fsh = pass.get("fragment_shader").getAsString();
            if (fsh.startsWith("uiengine:")) shaders.add(fsh);
        }
        assertEquals(java.util.Set.of("uiengine:post/overlay_take", "uiengine:post/overlay_blur", "uiengine:post/overlay_mask",
                "uiengine:post/overlay_compose", "uiengine:post/overlay_outline"), shaders);
        for (String s : new String[]{Shaders.overlayTakeFsh(), Shaders.overlayBlurFsh(), Shaders.overlayMaskFsh(), Shaders.overlayComposeFsh(),
                Shaders.overlayOutlineFsh(), Shaders.cloudsVsh()}) {
            assertFalse(s.contains("@"), "a placeholder was left in " + s);
        }
    }

    /**
     * The menu blur is JSON the game reads: our three pointer passes, ending on main, then the game's own six box-blur
     * passes as it ships them (main to swap and back, horizontal then vertical, at the player's own radius). It names
     * only targets it declares or main (LevelTargetBundle.MAIN_TARGETS), and its shaders have every placeholder filled.
     */
    @Test
    void menuBlurChainTakesThePointerOutThenBlursAsTheGameDoes() {
        com.google.gson.JsonObject chain = com.google.gson.JsonParser.parseString(Shaders.menuBlurChain()).getAsJsonObject();
        java.util.Set<String> known = new java.util.HashSet<>(chain.getAsJsonObject("targets").keySet());
        known.add("minecraft:main");
        com.google.gson.JsonArray passes = chain.getAsJsonArray("passes");
        assertEquals(9, passes.size());
        String[] ours = {"uiengine:post/pointer_find", "uiengine:post/pointer_grow", "uiengine:post/pointer_fill"};
        for (int i = 0; i < passes.size(); i++) {
            com.google.gson.JsonObject pass = passes.get(i).getAsJsonObject();
            assertTrue(known.contains(pass.get("output").getAsString()), pass.toString());
            for (var in : pass.getAsJsonArray("inputs")) assertTrue(known.contains(in.getAsJsonObject().get("target").getAsString()), pass.toString());
            if (i < 3) {
                assertEquals(ours[i], pass.get("fragment_shader").getAsString());
                continue;
            }
            int k = i - 3;
            assertEquals("minecraft:post/box_blur", pass.get("fragment_shader").getAsString());
            assertEquals(k % 2 == 0 ? "minecraft:main" : "swap", pass.getAsJsonArray("inputs").get(0).getAsJsonObject().get("target").getAsString());
            assertEquals(k % 2 == 0 ? "swap" : "minecraft:main", pass.get("output").getAsString());
            com.google.gson.JsonArray config = pass.getAsJsonObject("uniforms").getAsJsonArray("BlurConfig");
            assertEquals(k % 2 == 0 ? 1.0 : 0.0, config.get(0).getAsJsonObject().getAsJsonArray("value").get(0).getAsDouble());
            assertEquals(0.0, config.get(1).getAsJsonObject().get("value").getAsDouble());
        }
        assertEquals("minecraft:main", passes.get(2).getAsJsonObject().get("output").getAsString());
        for (String s : new String[]{Shaders.pointerFindFsh(), Shaders.pointerGrowFsh(), Shaders.pointerFillFsh()}) {
            assertFalse(s.contains("@"), "a placeholder was left in " + s);
        }
        // The code goes on the pointer in 26.2 only, where the chain ships.
        assertTrue(Shaders.textFsh(Shaders.Dialect.V26_2, PALETTE).contains("mcb_pointer_code(fragColor.rgb)"));
        assertFalse(Shaders.textFsh(Shaders.Dialect.V26_3, PALETTE).contains("mcb_pointer_code"));
    }
}
