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

    /**
     * Every symbol colour, put through the hash and displacement tree each text shader really carries, comes back as
     * its symbol: the palette and the shaders agree, whichever side is edited.
     */
    @Test
    void everySymbolColourDecodesToItsSymbolInTheShaders() {
        Pattern disp = Pattern.compile("uint mcb_displacements\\(uint i\\) \\{\n(.*?)\n}", Pattern.DOTALL);
        Pattern bucket = Pattern.compile("uint b = \\(k \\* (\\d+)u\\) >> (\\d+)u;");
        Pattern slot = Pattern.compile("uint s = \\(\\(\\(k \\* (\\d+)u\\) >> 24u\\) \\* (\\d+)u >> 8u\\) \\+ "
                + "\\(\\(mcb_displacements\\(b >> 2u\\) >> \\(\\(b & 3u\\) << 3u\\)\\) & 255u\\);");
        Pattern wrap = Pattern.compile("return int\\(s >= (\\d+)u \\? s - (\\d+)u : s\\);");
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            for (String src : new String[]{Shaders.textVsh(d, PALETTE), Shaders.textFsh(d, PALETTE)}) {
                Matcher md = disp.matcher(src), mb = bucket.matcher(src), ms = slot.matcher(src), mw = wrap.matcher(src);
                assertTrue(md.find() && mb.find() && ms.find() && mw.find(), d + ": the decoder's shape changed; update this test");
                long mul1 = Long.parseLong(mb.group(1)), mul2 = Long.parseLong(ms.group(1)), n = Long.parseLong(ms.group(2));
                int shift = Integer.parseInt(mb.group(2));
                assertEquals(n, Long.parseLong(mw.group(1)));
                assertEquals(n, Long.parseLong(mw.group(2)));
                for (int sym = 0; sym < Palette.SYMBOLS; sym++) {
                    long k = PALETTE.symbolRgb[sym];
                    int b = (int) ((k * mul1 & 0xFFFFFFFFL) >>> shift);
                    long word = select(md.group(1), b >> 2) & 0xFFFFFFFFL;
                    long s = (((k * mul2 & 0xFFFFFFFFL) >>> 24) * n >>> 8) + (word >>> ((b & 3) << 3) & 255);
                    assertEquals(sym, s >= n ? s - n : s, d + ": symbol " + sym);
                }
            }
        }
    }

    /** The select tree the shader reads the displacements with gives back every word, at every size it could be. */
    @Test
    void theSelectTreeReadsEveryWord() {
        for (int n : new int[]{4, 16, 32, 128}) {
            int[] words = new int[n];
            for (int i = 0; i < n; i++) words[i] = 0x9E3779B1 * (i + 1);
            String body = Shaders.selectTree(words);
            for (int i = 0; i < n; i++) assertEquals(words[i], select(body, i), n + " words, word " + i);
        }
    }

    /** Runs a {@link Shaders#selectTree} body for [i]: its lines are uvec4 selects on one bit, then a uvec2, then a uint. */
    private static int select(String body, int i) {
        Map<String, int[]> vars = new HashMap<>();
        Pattern line = Pattern.compile("(uvec4|uvec2) (\\w+) = \\(i & (\\d+)u\\) != 0u \\? (.+) : (.+);");
        Pattern ret = Pattern.compile("return \\(i & 1u\\) != 0u \\? h\\.y : h\\.x;");
        for (String l : body.strip().split("\n")) {
            l = l.strip();
            Matcher m = line.matcher(l);
            if (m.matches()) {
                boolean on = (i & Integer.parseInt(m.group(3))) != 0;
                vars.put(m.group(2), value(on ? m.group(4) : m.group(5), vars));
            } else if (ret.matcher(l).matches()) {
                int[] h = vars.get("h");
                return (i & 1) != 0 ? h[1] : h[0];
            } else {
                throw new AssertionError("unexpected line " + l);
            }
        }
        throw new AssertionError("no return");
    }

    private static int[] value(String expr, Map<String, int[]> vars) {
        Matcher swizzle = Pattern.compile("(.+)\\.(xy|zw)").matcher(expr);
        if (swizzle.matches()) {
            int[] v = value(swizzle.group(1), vars);
            return swizzle.group(2).equals("xy") ? new int[]{v[0], v[1]} : new int[]{v[2], v[3]};
        }
        if (vars.containsKey(expr)) return vars.get(expr);
        Matcher lit = Pattern.compile("uvec4\\((\\d+)u, (\\d+)u, (\\d+)u, (\\d+)u\\)").matcher(expr);
        if (!lit.matches()) throw new AssertionError("unexpected value " + expr);
        int[] v = new int[4];
        for (int k = 0; k < 4; k++) v[k] = Integer.parseUnsignedInt(lit.group(k + 1));
        return v;
    }

    /** No large table read by a computed index in the text shaders: AMD's Windows OpenGL driver makes one cost seconds a frame. */
    @Test
    void textShadersCarryNoTableAndNoPlaceholders() {
        Pattern array = Pattern.compile("\\[\\s*(\\d+)\\s*]");
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            String vsh = Shaders.textVsh(d, PALETTE), fsh = Shaders.textFsh(d, PALETTE);
            for (String s : new String[]{vsh, fsh}) {
                assertFalse(s.contains("@"), "a placeholder was left in a " + d + " shader");
                Matcher m = array.matcher(s);
                while (m.find()) assertTrue(Integer.parseInt(m.group(1)) < 32, "an array of " + m.group(1) + " in a " + d + " text shader");
            }
        }
    }

    @Test
    void onlyTheClientsThePackWasCheckedInKeepTheWorld() {
        assertEquals(Shaders.Dialect.V1_21_11, Shaders.Dialect.of(774));
        assertEquals(Shaders.Dialect.V26_1, Shaders.Dialect.of(775));
        assertEquals(Shaders.Dialect.V26_2, Shaders.Dialect.of(776));
        assertEquals(Shaders.Dialect.V26_3, Shaders.Dialect.of(777));
        assertEquals(null, Shaders.Dialect.of(773));
        assertEquals(null, Shaders.Dialect.of(778));
        assertEquals(null, Shaders.Dialect.of(-1));
    }

    /**
     * Before 26.2 one text shader draws GUI and world text: no IS_GUI is left to the preprocessor, the vertex shader's
     * GUI part is a run-time test of the projection, and the fragment shader writes depth on every path (a shader that
     * writes it must).
     */
    @Test
    void theTextShaderBefore262DrawsGuiAndWorldTextAlike() {
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            if (!d.presplit()) continue;
            String vsh = Shaders.textVsh(d, PALETTE), fsh = Shaders.textFsh(d, PALETTE);
            for (String s : new String[]{vsh, fsh}) {
                assertFalse(s.contains("IS_GUI") || s.contains("IS_SEE_THROUGH") || s.contains("#if"), d + " left a variant to the preprocessor");
            }
            assertTrue(vsh.contains("bool mcb_gui()") && vsh.contains("if (mcb_gui()) {"), d + " has no run-time GUI test");
            assertTrue(fsh.contains("void main() {\n// Derivatives before any branch or discard.\nvec2 mcbFootprint = fwidth(texCoord0 * 128.0);\n"
                    + "// Before 26.2 this one shader draws GUI and world text, and writes depth for both: a shader that writes\n"
                    + "// gl_FragDepth anywhere must on every path.\ngl_FragDepth = gl_FragCoord.z;"), d + "'s GUI text writes no depth");
            assertFalse(d.compareTo(Shaders.Dialect.V26_1) < 0 && vsh.contains("sample_lightmap"), d + " has no sample_lightmap");
        }
    }

    /**
     * Our text and position_tex_color shaders declare exactly vanilla's varyings, every dialect: what text.vsh tells
     * text.fsh rides in vanilla's own vertexColor (Shaders.ENCODED_VIEW). So either half of ours links with anybody's other
     * half; a pack whose two halves do not link is dropped whole by the client.
     */
    @Test
    void ourShadersDeclareOnlyVanillasVaryings() {
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            assertEquals(varyings(Shaders.vanillaTextVsh(d)), varyings(Shaders.textVsh(d, PALETTE)), d + " text.vsh");
            assertEquals(varyings(Shaders.vanillaTextFsh(d)), varyings(Shaders.textFsh(d, PALETTE)), d + " text.fsh");
            assertEquals(varyings(Shaders.vanillaPositionTexColorVsh(d)), varyings(Shaders.positionTexColorVsh(d)), d + " position_tex_color.vsh");
        }
    }

    private static final Pattern VARYING_DECL = Pattern.compile("(?m)^(?:layout\\(location = \\d+\\) )?(?:flat )?(in|out) \\w+ \\w+;");

    private static java.util.Set<String> varyings(String src) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        Matcher m = VARYING_DECL.matcher(src.lines().map(String::strip).reduce((x, y) -> x + "\n" + y).orElse(""));
        while (m.find()) out.add(m.group());
        return out;
    }

    /** Our text shaders are vanilla's plus marked additions, the form plugin-common's shader merge reads. */
    @Test
    void ourTextShadersCarryMergeMarkers() {
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            String vsh = Shaders.textVsh(d, PALETTE), fsh = Shaders.textFsh(d, PALETTE);
            for (String[] s : new String[][]{{vsh, "vertex", Shaders.VERTEX_BASE}, {fsh, "fragment", Shaders.FRAGMENT_BASE}}) {
                assertTrue(s[0].contains("// rpai-merge:begin " + s[1] + "-declarations v1\n"), d + " " + s[1]);
                assertTrue(s[0].contains("// rpai-merge:begin " + s[1] + "-vanilla v1\n"), d + " " + s[1]);
                assertTrue(s[0].contains("// rpai-merge:begin " + s[1] + "-epilogue v1\n// rpai-merge:renames main " + s[2] + "\n"), d + " " + s[1]);
                assertTrue(s[0].contains("void " + s[2] + "() {"), d + " " + s[1]);
                assertEquals(1, s[0].split("void main\\(\\)", -1).length - 1, d + " " + s[1] + ": one main");
            }
        }
    }

    /**
     * The overlay chain is JSON the game reads, keeps the game's four outline passes first, only names targets it
     * declares or the game gives it (main and entity_outline: LevelTargetBundle.OUTLINE_TARGETS), keeps its picture
     * from frame to frame, its own passes each have a vertex shader of ours (which keeps them off the pixels they
     * would not write) reading only samplers the pass is given, and its own shaders have every placeholder filled.
     */
    @Test
    void overlayChainIsWellFormed() {
        com.google.gson.JsonObject chain = com.google.gson.JsonParser.parseString(Shaders.overlayChain(false)).getAsJsonObject();
        com.google.gson.JsonObject targets = chain.getAsJsonObject("targets");
        assertTrue(targets.getAsJsonObject("ov_kept").get("persistent").getAsBoolean());
        com.google.gson.JsonArray passes = chain.getAsJsonArray("passes");
        String[] vanilla = {"minecraft:post/entity_sobel", "minecraft:post/entity_outline_box_blur", "minecraft:post/entity_outline_box_blur", "minecraft:post/blit"};
        for (int i = 0; i < vanilla.length; i++) assertEquals(vanilla[i], passes.get(i).getAsJsonObject().get("fragment_shader").getAsString());
        java.util.Set<String> known = new java.util.HashSet<>(targets.keySet());
        known.add("minecraft:main");
        known.add("minecraft:entity_outline");
        java.util.Map<String, String> vertex = java.util.Map.of(
                "uiengine:post/overlay_take", Shaders.overlayTakeVsh(Shaders.Dialect.V26_3),
                "uiengine:post/overlay_blur", Shaders.overlayBlurVsh(Shaders.Dialect.V26_3),
                "uiengine:post/overlay_signed_main", Shaders.overlaySignedVsh(Shaders.Dialect.V26_3, "MainSampler"),
                "uiengine:post/overlay_signed_in", Shaders.overlaySignedVsh(Shaders.Dialect.V26_3, "InSampler"));
        java.util.Set<String> shaders = new java.util.HashSet<>(), vertexUsed = new java.util.HashSet<>();
        for (var p : passes) {
            com.google.gson.JsonObject pass = p.getAsJsonObject();
            assertTrue(known.contains(pass.get("output").getAsString()), pass.toString());
            java.util.Set<String> samplers = new java.util.HashSet<>();
            for (var in : pass.getAsJsonArray("inputs")) {
                assertTrue(known.contains(in.getAsJsonObject().get("target").getAsString()), pass.toString());
                samplers.add(in.getAsJsonObject().get("sampler_name").getAsString() + "Sampler");
            }
            String fsh = pass.get("fragment_shader").getAsString();
            if (!fsh.startsWith("uiengine:")) continue;
            shaders.add(fsh);
            String vsh = pass.get("vertex_shader").getAsString();
            assertTrue(vertex.containsKey(vsh), pass.toString());
            vertexUsed.add(vsh);
            // 26.3 refuses a shader that declares a sampler its pass does not bind.
            var declared = java.util.regex.Pattern.compile("uniform sampler2D (\\w+);").matcher(vertex.get(vsh));
            while (declared.find()) assertTrue(samplers.contains(declared.group(1)), declared.group(1) + " in " + pass);
        }
        assertEquals(java.util.Set.of("uiengine:post/overlay_take", "uiengine:post/overlay_blur", "uiengine:post/overlay_mask",
                "uiengine:post/overlay_compose", "uiengine:post/overlay_outline"), shaders);
        assertEquals(vertex.keySet(), vertexUsed);
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            for (String s : new String[]{Shaders.overlayTakeFsh(d), Shaders.overlayBlurFsh(d), Shaders.overlayMaskFsh(d), Shaders.overlayComposeFsh(d),
                    Shaders.overlayOutlineFsh(d)}) {
                assertFalse(s.contains("@"), "a placeholder was left in " + s);
                assertEquals(d == Shaders.Dialect.V26_3, s.contains("layout(location = 0) out vec4 fragColor;"), s);
            }
            for (String s : new String[]{Shaders.overlayTakeVsh(d), Shaders.overlayBlurVsh(d), Shaders.overlaySignedVsh(d, "InSampler")}) {
                assertFalse(s.contains("@"), "a placeholder was left in " + s);
                assertEquals(d == Shaders.Dialect.V26_3, s.contains("gl_VertexIndex"), s);
                assertEquals(d == Shaders.Dialect.V26_3, s.contains("layout(location = 0) out vec2 texCoord;"), s);
            }
        }
        assertFalse(Shaders.cloudsVsh().contains("@"));
    }

    /**
     * 1.21.11's copy of the outline effect is the same but for its last pass's two blocks, which that version's outline
     * shader declares (and reads, as it reads SamplerInfo and Globals): so the game numbers SamplerInfo and those two
     * 0 to 2 and binds Globals at 3, where the particle shader drawn next finds it. No other version's declares them.
     */
    @Test
    void outlineEffectLeavesGlobalsWhereTheParticlesOf1_21_11LookForThem() {
        com.google.gson.JsonObject plain = com.google.gson.JsonParser.parseString(Shaders.overlayChain(false)).getAsJsonObject();
        com.google.gson.JsonObject held = com.google.gson.JsonParser.parseString(Shaders.overlayChain(true)).getAsJsonObject();
        com.google.gson.JsonArray passes = held.getAsJsonArray("passes");
        com.google.gson.JsonObject last = passes.get(passes.size() - 1).getAsJsonObject();
        assertEquals("uiengine:post/overlay_outline", last.get("fragment_shader").getAsString());
        java.util.Set<String> blocks = last.remove("uniforms").getAsJsonObject().keySet();
        assertEquals(plain, held);
        String fsh = Shaders.overlayOutlineFsh(Shaders.Dialect.V1_21_11);
        assertEquals(java.util.Set.of("OverlayHold1", "OverlayHold2"), blocks);
        for (String b : blocks) assertTrue(fsh.contains("uniform " + b + " {"), fsh);
        assertTrue(fsh.contains("uniform SamplerInfo") && fsh.contains("globals.glsl"), fsh);
        assertTrue(fsh.contains("Hold1 + Hold2 + GameTime + OutSize.x"), fsh);
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            if (d != Shaders.Dialect.V1_21_11) assertFalse(Shaders.overlayOutlineFsh(d).contains("OverlayHold"), d.name());
        }
    }

    /**
     * The improved-transparency effect (before 26.3) is JSON the game reads: the game's own composite into "final" first,
     * as the game ships it, then the signature relayed, the same keeping passes as the outline effect on "final", the
     * game's own blit to main before the compose. It names only targets it declares or the game gives it
     * (LevelTargetBundle.SORTING_TARGETS), and each vertex shader of ours reads only samplers its pass binds.
     */
    @Test
    void transparencyChainKeepsTheCompositeAsTheOutlineEffectKeepsTheWorld() {
        com.google.gson.JsonObject chain = com.google.gson.JsonParser.parseString(Shaders.transparencyChain()).getAsJsonObject();
        com.google.gson.JsonObject targets = chain.getAsJsonObject("targets");
        assertTrue(targets.getAsJsonObject("tr_kept").get("persistent").getAsBoolean());
        com.google.gson.JsonArray passes = chain.getAsJsonArray("passes");
        java.util.Set<String> known = new java.util.HashSet<>(targets.keySet());
        known.addAll(java.util.List.of("minecraft:main", "minecraft:translucent", "minecraft:item_entity", "minecraft:particles",
                "minecraft:clouds", "minecraft:weather"));
        java.util.List<String> order = new java.util.ArrayList<>();
        for (var p : passes) {
            com.google.gson.JsonObject pass = p.getAsJsonObject();
            order.add(pass.get("fragment_shader").getAsString());
            assertTrue(known.contains(pass.get("output").getAsString()), pass.toString());
            java.util.Set<String> samplers = new java.util.HashSet<>();
            for (var in : pass.getAsJsonArray("inputs")) {
                assertTrue(known.contains(in.getAsJsonObject().get("target").getAsString()), pass.toString());
                samplers.add(in.getAsJsonObject().get("sampler_name").getAsString() + "Sampler");
            }
            String vsh = pass.get("vertex_shader").getAsString();
            if (vsh.equals("minecraft:core/screenquad")) continue;
            for (Shaders.Dialect d : java.util.List.of(Shaders.Dialect.V1_21_11, Shaders.Dialect.V26_1, Shaders.Dialect.V26_2)) {
                String src = switch (vsh) {
                    case "uiengine:post/overlay_relay" -> Shaders.overlayRelayVsh(d);
                    case "uiengine:post/overlay_take" -> Shaders.overlayTakeVsh(d);
                    case "uiengine:post/overlay_blur" -> Shaders.overlayBlurVsh(d);
                    case "uiengine:post/overlay_signed_main" -> Shaders.overlaySignedVsh(d, "MainSampler");
                    case "uiengine:post/overlay_signed_in" -> Shaders.overlaySignedVsh(d, "InSampler");
                    default -> throw new AssertionError("no vertex shader " + vsh);
                };
                var declared = java.util.regex.Pattern.compile("uniform sampler2D (\\w+);").matcher(src);
                while (declared.find()) assertTrue(samplers.contains(declared.group(1)), declared.group(1) + " in " + pass);
            }
        }
        assertEquals(java.util.List.of("minecraft:post/transparency", "uiengine:post/overlay_relay", "uiengine:post/overlay_take",
                "uiengine:post/overlay_blur", "uiengine:post/overlay_blur", "uiengine:post/overlay_blur", "uiengine:post/overlay_blur",
                "uiengine:post/overlay_blur", "uiengine:post/overlay_mask", "minecraft:post/blit", "uiengine:post/overlay_compose"), order);
        assertEquals("final", passes.get(0).getAsJsonObject().get("output").getAsString());
        assertEquals("minecraft:main", passes.get(passes.size() - 1).getAsJsonObject().get("output").getAsString());
        for (Shaders.Dialect d : java.util.List.of(Shaders.Dialect.V1_21_11, Shaders.Dialect.V26_1, Shaders.Dialect.V26_2)) {
            for (String s : new String[]{Shaders.overlayRelayFsh(d), Shaders.overlayRelayVsh(d)}) {
                assertFalse(s.contains("@"), "a placeholder was left in " + s);
                assertFalse(s.contains("layout(location"), s);
            }
        }
        // 1.21.11 has no sample_lightmap.glsl.
        assertFalse(Shaders.particleVsh().contains("@") || Shaders.particleVsh().contains("sample_lightmap"));
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
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            for (String s : new String[]{Shaders.pointerFindFsh(d), Shaders.pointerGrowFsh(d), Shaders.pointerFillFsh(d)}) {
                assertFalse(s.contains("@"), "a placeholder was left in " + s);
                assertEquals(d == Shaders.Dialect.V26_3, s.contains("layout(location = 0) in vec2 texCoord;"), s);
            }
            // The code goes on the pointer in both versions, outside 26.3's order-independent transparency.
            assertTrue(Shaders.textFsh(d, PALETTE).contains("c.rgb = mcb_pointer_code(c.rgb);"));
        }
    }

    /**
     * Writes every text and position_tex_color shader, per dialect, to build/shader-dump/, for a compile and link on a
     * real driver (the GPU check described in NOTES.md); asserts nothing itself.
     */
    @Test
    void writesTheShadersForTheGpuCheck() throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Path.of("build", "shader-dump");
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            java.nio.file.Path out = dir.resolve(d.name());
            java.nio.file.Files.createDirectories(out);
            java.nio.file.Files.writeString(out.resolve("text.vsh"), Shaders.textVsh(d, PALETTE));
            java.nio.file.Files.writeString(out.resolve("text.fsh"), Shaders.textFsh(d, PALETTE));
            java.nio.file.Files.writeString(out.resolve("position_tex_color.vsh"), Shaders.positionTexColorVsh(d));
        }
    }
}
