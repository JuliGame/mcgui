package ai.resourcepack.ui.framework.pack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A copy of one of our shaders with another plugin's code merged in is told from one that lost ours. */
class ShaderCodeTest {
    private static final Function<String, String> NO_INCLUDES = id -> null;

    /** Our two vertex shaders a plugin is likely to have its own copy of, in [d]. */
    private static List<String> shaders(Shaders.Dialect d) {
        return List.of(Shaders.textVsh(d, TestPacks.PALETTE), Shaders.positionTexColorVsh(d));
    }

    /** Merged the way Pufferball's tool merged: main renamed, theirs added, a main calling both; reformatted on the way. */
    private static String renamed(String full) {
        return TestPacks.renamedMerge(full, TestPacks.BALL_ARROW, "pb_main").replace("\n", "\r\n").replace("    ", "\t");
    }

    @Test
    void ourOwnShaderCarriesItself() {
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            for (String full : shaders(d)) {
                assertNull(ShaderCode.carries(full, full, NO_INCLUDES), d.toString());
                assertNull(ShaderCode.carries(full, full.replace("\n", "\r\n"), NO_INCLUDES), d + " with CRLF");
            }
            String fsh = Shaders.textFsh(d, TestPacks.PALETTE);
            assertNull(ShaderCode.carries(fsh, fsh, NO_INCLUDES), d + " text.fsh");
        }
    }

    @Test
    void ourFileWithMainRenamedAndTheirCodeAddedCarriesOurs() {
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            for (String full : shaders(d)) assertNull(ShaderCode.carries(full, renamed(full), NO_INCLUDES), d.toString());
        }
    }

    @Test
    void piecesMovedIntoAnIncludeOfThePackAreStillThere() {
        String full = Shaders.positionTexColorVsh(Shaders.Dialect.V26_2);
        String helper = "float mcb_seated_flag() { return mcb_flag_axis() >= 0 ? 1.0 : 0.0; }\n";
        assertTrue(full.contains(helper));
        String theirs = renamed(full.replace(helper, "#moj_import <pb:helpers.glsl>\n"));
        assertNotNull(ShaderCode.carries(full, theirs, NO_INCLUDES));
        assertNull(ShaderCode.carries(full, theirs, id -> id.equals("pb:helpers.glsl") ? helper : null));
    }

    @Test
    void aMergeFromAnOlderMcGuiIsNotOurs() {
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            String full = Shaders.textVsh(d, TestPacks.PALETTE);
            // MC GUI changed one line of mcb_world_vertex since the merge was made.
            String older = renamed(full.replace("mcbPointer = 1;", "mcbPointer = 3;"));
            ShaderCode.Missing lacks = ShaderCode.carries(full, older, NO_INCLUDES);
            assertNotNull(lacks, d.toString());
            assertTrue(lacks.what().contains("mcb_world_vertex"), lacks.what());
        }
    }

    @Test
    void aMergeThatDroppedOneOfOurFunctionsIsNotOurs() {
        String full = Shaders.positionTexColorVsh(Shaders.Dialect.V26_2);
        String dropped = renamed(full).replaceAll("vec2 mcb_gui_size\\(\\) \\{[^\n]*\r\n", "");
        ShaderCode.Missing lacks = ShaderCode.carries(full, dropped, NO_INCLUDES);
        assertNotNull(lacks);
        assertEquals("the function mcb_gui_size()", lacks.what());
    }

    @Test
    void ourMainHasToBeRunFromTheirs() {
        for (String full : shaders(Shaders.Dialect.V26_3)) {
            assertNotNull(ShaderCode.carries(full, renamed(full).replace("mcgui_main();", ""), NO_INCLUDES));
        }
    }

    @Test
    void anotherPluginsShaderIsNotOurs() {
        String vanilla = """
                #version 330
                #include <minecraft:dynamictransforms.glsl>
                #include <minecraft:projection.glsl>
                layout(location = 0) in vec3 Position;
                layout(location = 1) in vec4 Color;
                layout(location = 2) in vec2 UV0;
                layout(location = 2) out vec4 vertexColor;
                layout(location = 3) out vec2 texCoord0;
                void main() {
                    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
                    vertexColor = Color;
                    texCoord0 = UV0;
                }
                """;
        assertNotNull(ShaderCode.carries(Shaders.textVsh(Shaders.Dialect.V26_3, TestPacks.PALETTE), vanilla, NO_INCLUDES));
    }

    @Test
    void aGameIncludeStandsForTheUniformBlockItDeclares() {
        // position_tex_color declares its blocks inline (the game compiles it at startup, before includes); a plugin's
        // copy may import them instead.
        String full = Shaders.positionTexColorVsh(Shaders.Dialect.V26_2);
        String globals = full.substring(full.indexOf("layout(std140) uniform Globals {"));
        globals = globals.substring(0, globals.indexOf("};\n") + 3);
        assertNull(ShaderCode.carries(full, renamed(full.replace(globals, "#moj_import <minecraft:globals.glsl>\n")), NO_INCLUDES));
    }

    @Test
    void piecesIgnoreLayoutAndComments() {
        assertEquals(ShaderCode.pieces("vec4 a(vec2 b) { return vec4(b, 0.0, 1.0); } // x\nfloat c;"),
                ShaderCode.pieces("/* y */ vec4 a( vec2 b )\n{\n\treturn vec4(b,0.0,1.0);\n}\r\nfloat  c ;"));
        assertEquals(List.of("#include<minecraft:fog.glsl>", "layout(std140)uniform G{int a;};", "void main(){}"),
                ShaderCode.pieces("#include <minecraft:fog.glsl>\nlayout(std140) uniform G {\n int a;\n};\nvoid main() {\n}\n"));
        assertEquals("minecraft:fog.glsl", ShaderCode.importOf("#moj_import<fog.glsl>"));
        assertEquals("assets/pb/shaders/include/helpers.glsl", ShaderCode.includePath("pb:helpers.glsl"));
    }
}
