package ai.resourcepack.screens.pack;

import ai.resourcepack.screens.render.Palette;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The core shaders the pack replaces, written once and emitted in each dialect the game has spoken:
 *
 * <ol>
 *   <li>{@code text.fsh} decodes hi-colour maps (every item-frame map is drawn with the text
 *       pipeline), and draws a seated player's own view and pointer in front of the world;
 *       {@code text.vsh} stretches that view to fill their window.</li>
 *   <li>{@code position_tex_color} hides the hotbar, hearts, crosshair and vignette, but only for a
 *       seated player.</li>
 * </ol>
 *
 * <p>"Seated" is read from {@code Globals.CameraOffset}: the engine puts a seated player's camera at a
 * block fraction of k/16 + 3/128 along the view's normal and 0 or 1/2 on the other two axes, which no
 * normal eye position produces. Everyone else sees vanilla.
 *
 * <p><b>26.3 rewrote the shader dialect</b> (explicit {@code layout(location)} on every varying,
 * {@code #include} for {@code #moj_import}, reordered uniform blocks, order-independent transparency
 * in the text shader). Sources here are in the 26.3 form; {@link #to262} derives the older one, and the
 * pack ships each in the overlay for its formats.
 */
public final class Shaders {
    private Shaders() {}

    public enum Dialect { V26_2, V26_3 }

    /** A seated camera's coordinate along its view's normal is always k/16 + 3/128 of a block. */
    public static final double FLAG_STEP = 1.0 / 16;
    public static final double FLAG_PHASE = 3.0 / 128;

    /**
     * The red of a seated player's pointer, which is text: the text shader draws text of this red in
     * front of the world, in white, and reads its green and blue as the view's columns and rows so it
     * can stretch the pointer exactly as it stretches the picture (see {@link #pointerRgb}).
     */
    public static final int POINTER_RED = 254;

    /** The pointer's text colour on a view of [cols] × [rows] maps. */
    public static int pointerRgb(int cols, int rows) {
        return POINTER_RED << 16 | Math.min(255, cols) << 8 | Math.min(255, rows);
    }

    private static String f6(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static final Pattern LOCATION = Pattern.compile("layout\\(location = \\d+\\) ");

    /** The 26.2 form of a 26.3 source. */
    static String to262(String src) {
        String s = src.replace("#extension GL_ARB_separate_shader_objects : require\n", "")
                .replace("#include <", "#moj_import <");
        return LOCATION.matcher(s).replaceAll("");
    }

    private static String emit(String src, Dialect d) {
        String s = src.lines().map(String::strip).reduce((a, b) -> a + "\n" + b).orElse("") + "\n";
        return d == Dialect.V26_3 ? s : to262(s);
    }

    /** Uniform blocks, in each version's member order (std140: the order IS the layout). */
    private static String dynamicTransforms(Dialect d) {
        return d == Dialect.V26_3 ? """
                layout(std140) uniform DynamicTransforms {
                    mat4 ModelViewMat;
                    mat4 TextureMat;
                    vec4 ColorModulator;
                    vec3 ModelOffset;
                };
                """ : """
                layout(std140) uniform DynamicTransforms {
                    mat4 ModelViewMat;
                    vec4 ColorModulator;
                    vec3 ModelOffset;
                    mat4 TextureMat;
                };
                """;
    }

    private static String globals(Dialect d) {
        return d == Dialect.V26_3 ? """
                layout(std140) uniform Globals {
                    ivec3 CameraBlockPos;
                    float GlintAlpha;
                    vec3 CameraOffset;
                    float GameTime;
                    vec2 ScreenSize;
                    int MenuBlurRadius;
                    int UseRgss;
                };
                """ : """
                layout(std140) uniform Globals {
                    ivec3 CameraBlockPos;
                    vec3 CameraOffset;
                    vec2 ScreenSize;
                    float GlintAlpha;
                    float GameTime;
                    int MenuBlurRadius;
                    int UseRgss;
                };
                """;
    }

    private static final String PROJECTION = """
            layout(std140) uniform Projection {
                mat4 ProjMat;
            };
            """;

    /** Vertex side: the GUI position of the vertex, the GUI size, and whether this client is seated. */
    private static final String VERTEX_HELPERS = """
            bool mcb_near(float v, float t) { return abs(v - t) < 0.0001; }
            bool mcb_grid(float v) { return mcb_near(v, 0.0) || mcb_near(v, 0.5) || mcb_near(v, 1.0); }
            float mcb_flag(float v) { return abs(fract(v * @STEPS@) - @FRACTION@) < 0.002 ? 1.0 : 0.0; }
            // Seated cameras sit at a flagged fraction of a block along one horizontal axis.
            float mcb_seated_flag() {
                vec3 f = -CameraOffset;
                if (!mcb_grid(f.y)) return 0.0;
                float a = mcb_grid(f.z) ? mcb_flag(f.x) : 0.0;
                return a > 0.0 ? a : (mcb_grid(f.x) ? mcb_flag(f.z) : 0.0);
            }
            // Only orthographic (GUI) draws: perspective projections have ProjMat[3][3] == 0.
            float mcb_seated_now() { return ProjMat[3][3] != 1.0 ? 0.0 : mcb_seated_flag(); }
            vec2 mcb_gui_size() { return vec2(2.0 / abs(ProjMat[0][0]), 2.0 / abs(ProjMat[1][1])); }
            // Reversed depth (near 1, far 0) has ProjMat[2][2] near 0; OpenGL's classic has it near -1.
            float mcb_reversed() { return ProjMat[2][2] > -0.5 ? 1.0 : 0.0; }
            """;

    /** Fragment side: rectangles in GUI pixels. */
    private static final String FRAGMENT_HELPERS = """
            bool mcb_in(vec2 p, vec2 a, vec2 b) { return p.x >= a.x && p.x <= b.x && p.y >= a.y && p.y <= b.y; }
            bool mcb_hud(vec2 pos, vec2 size) {
                float cx = floor(size.x / 2.0);
                return mcb_in(pos, vec2(cx - 122.0, size.y - 24.0), vec2(cx + 122.0, size.y))
                    || mcb_in(pos, vec2(cx - 92.0, size.y - 64.0), vec2(cx + 92.0, size.y - 22.0));
            }
            bool mcb_crosshair(vec2 pos, vec2 size) {
                vec2 c = floor(size / 2.0);
                return mcb_in(pos, c - 9.0, c + 9.0);
            }
            """;

    private static String helpers(String src) {
        return src.replace("@STEPS@", String.format(Locale.ROOT, "%.1f", 1 / FLAG_STEP))
                .replace("@FRACTION@", f6(FLAG_PHASE / FLAG_STEP));
    }

    // ---------------------------------------------------------------- position_tex_color

    public static String positionTexColorVsh(Dialect d) {
        return emit("""
                #version 330
                #extension GL_ARB_separate_shader_objects : require

                """ + dynamicTransforms(d) + PROJECTION + globals(d) + """

                layout(location = 0) in vec3 Position;
                layout(location = 1) in vec2 UV0;
                layout(location = 2) in vec4 Color;

                layout(location = 0) out vec2 texCoord0;
                layout(location = 1) out vec4 vertexColor;
                layout(location = 2) out vec2 mcbPos;
                layout(location = 3) flat out vec2 mcbSize;
                layout(location = 4) flat out float mcbSeated;

                """ + helpers(VERTEX_HELPERS) + """

                void main() {
                    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
                    texCoord0 = UV0;
                    vertexColor = Color;
                    mcbPos = (ModelViewMat * vec4(Position, 1.0)).xy;
                    mcbSize = mcb_gui_size();
                    mcbSeated = mcb_seated_now();
                }
                """, d);
    }

    public static String positionTexColorFsh(Dialect d) {
        return emit("""
                #version 330
                #extension GL_ARB_separate_shader_objects : require

                """ + dynamicTransforms(d) + """

                uniform sampler2D Sampler0;

                layout(location = 0) in vec2 texCoord0;
                layout(location = 1) in vec4 vertexColor;
                layout(location = 2) in vec2 mcbPos;
                layout(location = 3) flat in vec2 mcbSize;
                layout(location = 4) flat in float mcbSeated;

                layout(location = 0) out vec4 fragColor;

                """ + FRAGMENT_HELPERS + """

                void main() {
                    // 16x16 textures are the dimmed tiles behind menus (Esc, options): never cut.
                    if (mcbSeated > 0.5 && textureSize(Sampler0, 0) != ivec2(16, 16)) {
                        // The vignette is the only standalone 256x256 texture the HUD draws.
                        if (textureSize(Sampler0, 0) == ivec2(256, 256)) discard;
                        if (mcb_hud(mcbPos, mcbSize)) discard;
                    }
                    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
                    // The crosshair is pure white; a menu button in the middle of the window is not.
                    if (mcbSeated > 0.5 && mcb_crosshair(mcbPos, mcbSize) && all(greaterThan(color.rgb, vec3(0.99)))) discard;
                    if (color.a == 0.0) {
                        discard;
                    }
                    fragColor = color * ColorModulator;
                }
                """, d);
    }

    // ---------------------------------------------------------------- text

    public static String textVsh(Dialect d, Palette palette) {
        return emit("""
                #version 330
                #extension GL_ARB_separate_shader_objects : require

                #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                #include <minecraft:fog.glsl>
                #include <minecraft:sample_lightmap.glsl>
                #endif

                #include <minecraft:dynamictransforms.glsl>
                #include <minecraft:projection.glsl>
                #include <minecraft:globals.glsl>

                layout(location = 0) in vec3 Position;
                layout(location = 1) in vec4 Color;
                layout(location = 2) in vec2 UV0;
                #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                layout(location = 3) in ivec2 UV2;
                #endif

                uniform sampler2D Sampler0;

                #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                uniform sampler2D Sampler2;
                layout(location = 0) out float sphericalVertexDistance;
                layout(location = 1) out float cylindricalVertexDistance;
                #endif

                layout(location = 2) out vec4 vertexColor;
                layout(location = 3) out vec2 texCoord0;
                layout(location = 4) out vec2 mcbPos;
                layout(location = 5) flat out vec2 mcbSize;
                layout(location = 6) flat out float mcbSeated;
                layout(location = 7) flat out float mcbPointer;
                layout(location = 8) flat out float mcbReversed;

                """ + helpers(VERTEX_HELPERS) + decoderCore(palette) + """

                void main() {
                    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
                    mcbReversed = mcb_reversed();
                    texCoord0 = UV0;
                    mcbPos = (ModelViewMat * vec4(Position, 1.0)).xy;
                    mcbSize = mcb_gui_size();
                    mcbPointer = 0.0;
                #ifdef IS_GUI
                    mcbSeated = mcb_seated_now();
                    vertexColor = Color;
                #else
                    mcbSeated = mcb_seated_flag();
                    vec4 mcbTint = Color;
                    if (mcbSeated > 0.5) {
                        // The pointer: text in the pointer's red, its view's columns and rows in green and blue. Drawn white.
                        ivec3 mcbRgb = ivec3(round(Color.rgb * 255.0));
                        vec2 mcbGrid = vec2(0.0);
                        if (mcbRgb.r == @PR@ && mcbRgb.g > 0 && mcbRgb.b > 0) {
                            mcbPointer = 1.0;
                            mcbGrid = vec2(mcbRgb.gb);
                            mcbTint = vec4(1.0, 1.0, 1.0, Color.a);
                        } else if (mcb_screen_map() == 2) {
                            // Their own view's maps carry its columns and rows next to the marker.
                            mcbGrid = vec2(float(mcb_symbol(ivec2(2, 0))), float(mcb_symbol(ivec2(3, 0))));
                        }
                        // Both fill the window whatever its shape and whatever the player's FOV. The picture is centred
                        // on the camera's axis and square to it, so how far a vertex is from that axis, in blocks, as a
                        // fraction of half the picture's size, is where it goes in the window.
                        if (mcbGrid.x > 0.0 && mcbGrid.y > 0.0) {
                            vec2 mcbBlocks = gl_Position.xy / vec2(ProjMat[0][0], ProjMat[1][1]);
                            gl_Position.xy = mcbBlocks / (mcbGrid * 0.5) * gl_Position.w;
                        }
                    }
                #ifdef IS_SEE_THROUGH
                    vertexColor = mcbTint;
                #else
                    sphericalVertexDistance = fog_spherical_distance(Position);
                    cylindricalVertexDistance = fog_cylindrical_distance(Position);
                    vertexColor = mcbTint * sample_lightmap(Sampler2, UV2);
                #endif
                #endif
                }
                """.replace("@PR@", String.valueOf(POINTER_RED)), d);
    }

    /**
     * Map colour to symbol, and which maps are ours: both text shaders need it, the vertex one to find a
     * seated player's own view (and its size), the fragment one to decode every screen.
     */
    private static String decoderCore(Palette palette) {
        SymbolHash hash = new SymbolHash(palette.symbolRgb);
        return """

                // ---- Screen Engine hi-colour map decoder ----
                // Colour -> symbol in two table reads (SymbolHash builds these tables).
                const int MCB_D[@BUCKETS@] = @DISPLACE@;
                const int MCB_S[@SLOTS@] = @SLOTTABLE@;

                ivec3 mcb_fetch(ivec2 p) { return ivec3(round(texelFetch(Sampler0, p, 0).rgb * 255.0)); }

                int mcb_symbol(ivec2 p) {
                    uvec3 c = uvec3(mcb_fetch(p));
                    uint k = (c.r << 16u) | (c.g << 8u) | c.b;
                    uint b = ((k * @MULA@u) >> 16u) % @BUCKETS@u;
                    uint slot = (((k * @MULB@u) >> 16u) + uint(MCB_D[b])) % @SLOTS@u;
                    return max(MCB_S[slot], 0);
                }

                // 0: not ours. 1: a screen. 2: the view of whoever is seated at it (bottom markers swapped).
                int mcb_screen_map() {
                    if (textureSize(Sampler0, 0) != ivec2(128, 128)
                        || mcb_fetch(ivec2(0, 0)) != @M0@ || mcb_fetch(ivec2(1, 0)) != @M1@) return 0;
                    ivec3 a = mcb_fetch(ivec2(0, 1));
                    ivec3 b = mcb_fetch(ivec2(1, 1));
                    if (a == @M2@ && b == @M3@) return 1;
                    if (a == @M3@ && b == @M2@) return 2;
                    return 0;
                }
                """
                .replace("@BUCKETS@", String.valueOf(SymbolHash.BUCKETS))
                .replace("@SLOTS@", String.valueOf(SymbolHash.SLOTS))
                .replace("@DISPLACE@", ints(hash.displace))
                .replace("@SLOTTABLE@", ints(hash.slots))
                .replace("@MULA@", Integer.toUnsignedString(hash.mulA))
                .replace("@MULB@", Integer.toUnsignedString(hash.mulB))
                .replace("@M0@", ivec3(palette.markerRgb[0]))
                .replace("@M1@", ivec3(palette.markerRgb[1]))
                .replace("@M2@", ivec3(palette.markerRgb[2]))
                .replace("@M3@", ivec3(palette.markerRgb[3]));
    }

    public static String textFsh(Dialect d, Palette palette) {
        String head = """
                #version 330
                #extension GL_ARB_separate_shader_objects : require

                #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                #include <minecraft:fog.glsl>
                #endif

                #include <minecraft:dynamictransforms.glsl>
                """ + (d == Dialect.V26_3 ? "#include <minecraft:oit.glsl>\n" : "") + """

                uniform sampler2D Sampler0;

                #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                layout(location = 0) in float sphericalVertexDistance;
                layout(location = 1) in float cylindricalVertexDistance;
                #endif

                layout(location = 2) in vec4 vertexColor;
                layout(location = 3) in vec2 texCoord0;
                layout(location = 4) in vec2 mcbPos;
                layout(location = 5) flat in vec2 mcbSize;
                layout(location = 6) flat in float mcbSeated;
                layout(location = 7) flat in float mcbPointer;
                layout(location = 8) flat in float mcbReversed;

                """ + (d == Dialect.V26_3 ? """
                #ifndef OIT_ALPHA_ONLY
                layout(location = 0) out vec4 fragColor;
                #endif
                """ : "out vec4 fragColor;\n");

        String decoder = decoderCore(palette) + """

                // A seated player's own view and pointer win over any block between them and the camera,
                // so no blocks need clearing. Scaling depth towards "near" puts both in front of anything
                // more than a few centimetres from the eye, and the pointer (more) in front of the picture.
                float mcb_in_front(float k) {
                    return mcbReversed > 0.5 ? min(gl_FragCoord.z * k, 1.0) : gl_FragCoord.z / k;
                }

                // The screen is almost never drawn at one map pixel per window pixel. Instead of the
                // nearest pixel (uneven, shimmering text), average the map pixels under this window
                // pixel's footprint, weighted by overlap. Averaging in YCbCr is exact: the conversion to
                // RGB is linear. Pixels come in 2x2 blocks that share one chroma value. The first [info]
                // + 1 blocks of the top row are the marker (and a view's size): they show the next one.
                vec3 mcb_sample(vec2 t, vec2 fw, int info) {
                    vec2 halfFw = clamp(fw, vec2(0.02), vec2(2.0)) * 0.5;
                    vec2 lo = clamp(t - halfFw, vec2(0.0), vec2(128.0));
                    vec2 hi = clamp(t + halfFw, vec2(0.0), vec2(128.0));
                    ivec2 b0 = clamp(ivec2(floor(lo * 0.5)), ivec2(0), ivec2(63));
                    ivec2 b1 = clamp(ivec2(floor((hi - 0.0001) * 0.5)), ivec2(0), ivec2(63));
                    vec3 acc = vec3(0.0);
                    float total = 0.0;
                    for (int by = b0.y; by <= b1.y; by++) {
                        for (int bx = b0.x; bx <= b1.x; bx++) {
                            ivec2 base = ivec2(bx, by) * 2;
                            ivec2 src = (by == 0 && bx <= info) ? ivec2(2 * info + 2, 0) : base;
                            int s00 = mcb_symbol(src);
                            int s10 = mcb_symbol(src + ivec2(1, 0));
                            int s01 = mcb_symbol(src + ivec2(0, 1));
                            int s11 = mcb_symbol(src + ivec2(1, 1));
                            vec4 luma = vec4(float(s00 / @DIGITS@), float(s10 / @DIGITS@), float(s01 / @DIGITS@), float(s11 / @DIGITS@)) / @LUMAMAX@;
                            vec2 chroma = vec2(float((s00 % @DIGITS@) * @DIGITS@ + (s10 % @DIGITS@) - 32),
                                               float((s01 % @DIGITS@) * @DIGITS@ + (s11 % @DIGITS@) - 32)) / 63.0;
                            vec2 p0 = vec2(base);
                            vec2 ov0 = max(min(hi, p0 + 1.0) - max(lo, p0), 0.0);
                            vec2 ov1 = max(min(hi, p0 + 2.0) - max(lo, p0 + 1.0), 0.0);
                            vec4 w = vec4(ov0.x * ov0.y, ov1.x * ov0.y, ov0.x * ov1.y, ov1.x * ov1.y);
                            float ws = w.x + w.y + w.z + w.w;
                            acc += vec3(dot(w, luma), chroma * ws);
                            total += ws;
                        }
                    }
                    vec3 ycc = acc / max(total, 1e-6);
                    return clamp(vec3(ycc.x + 1.402 * ycc.z, ycc.x - 0.344136 * ycc.y - 0.714136 * ycc.z, ycc.x + 1.772 * ycc.y), 0.0, 1.0);
                }
                // ---- end decoder ----

                bool mcb_in(vec2 p, vec2 a, vec2 b) { return p.x >= a.x && p.x <= b.x && p.y >= a.y && p.y <= b.y; }
                """
                .replace("@DIGITS@", String.valueOf(Palette.DIGITS))
                .replace("@LUMAMAX@", String.format(Locale.ROOT, "%.1f", (float) (Palette.LUMA_LEVELS - 1)));

        String mainHead = """

                void main() {
                    // Derivatives before any branch or discard.
                    vec2 mcbFootprint = fwidth(texCoord0 * 128.0);
                    float mcbDepth = gl_FragCoord.z;
                #ifndef IS_GUI
                    if (mcbSeated > 0.5 && mcbPointer > 0.5) mcbDepth = mcb_in_front(128.0);
                    // A shader that writes gl_FragDepth anywhere must write it on every path.
                    gl_FragDepth = mcbDepth;
                #endif
                #ifdef IS_GUI
                    // The XP level over the hotbar, hidden while seated like the rest of the HUD.
                    if (mcbSeated > 0.5) {
                        float cx = floor(mcbSize.x / 2.0);
                        if (mcb_in(mcbPos, vec2(cx - 40.0, mcbSize.y - 40.0), vec2(cx + 40.0, mcbSize.y - 22.0))) discard;
                    }
                #endif
                #ifndef IS_GRAYSCALE
                    int mcbMap = mcb_screen_map();
                    if (mcbMap > 0) {
                        // A screen emits its own light: no lightmap, tint or fog, so colours are exact day and night.
                        vec4 mcbColor = vec4(mcb_sample(texCoord0 * 128.0, mcbFootprint, mcbMap == 2 ? 1 : 0), 1.0);
                #ifndef IS_GUI
                        if (mcbMap == 2 && mcbSeated > 0.5) {
                            mcbDepth = mcb_in_front(64.0);
                            gl_FragDepth = mcbDepth;
                        }
                #endif
                """;

        String tail = d == Dialect.V26_3 ? """
                #ifdef OIT_ALPHA_ONLY
                        executeAlphaOnlyPhase(mcbDepth, 1.0);
                #else
                #ifdef OIT_ACCUMULATE
                        mcbColor = sampleColorForAccumulation(mcbColor);
                #endif
                        fragColor = mcbColor;
                #endif
                        return;
                    }
                #endif

                #ifdef IS_GRAYSCALE
                    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
                #else
                    vec4 texColor = texture(Sampler0, texCoord0);
                #endif
                    vec4 color = texColor * vertexColor * ColorModulator;
                    if (color.a < 0.1) {
                        discard;
                    }
                #ifdef OIT_ALPHA_ONLY
                    executeAlphaOnlyPhase(mcbDepth, color.a);
                #else
                #ifdef OIT_ACCUMULATE
                    color = sampleColorForAccumulation(color);
                #endif
                #if !defined(IS_SEE_THROUGH) && !defined(IS_GUI)
                #ifdef OIT_ACCUMULATE
                    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
                #else
                    vec4 fogColor = FogColor;
                #endif
                    color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
                #endif
                    fragColor = color;
                #endif
                }
                """ : """
                        fragColor = mcbColor;
                        return;
                    }
                #endif

                #ifdef IS_GRAYSCALE
                    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
                #else
                    vec4 texColor = texture(Sampler0, texCoord0);
                #endif
                #ifdef IS_SEE_THROUGH
                    vec4 color = texColor * vertexColor;
                #else
                    vec4 color = texColor * vertexColor * ColorModulator;
                #endif
                    if (color.a < 0.1) {
                        discard;
                    }
                #ifdef IS_SEE_THROUGH
                    fragColor = color * ColorModulator;
                #elif defined(IS_GUI)
                    fragColor = color;
                #else
                    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
                #endif
                }
                """;
        return emit(head + decoder + mainHead + tail, d);
    }

    private static String ints(int[] values) {
        StringBuilder sb = new StringBuilder("int[").append(values.length).append("](");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        return sb.append(')').toString();
    }

    private static String ivec3(int rgb) {
        return "ivec3(" + (rgb >> 16 & 255) + "," + (rgb >> 8 & 255) + "," + (rgb & 255) + ")";
    }
}
