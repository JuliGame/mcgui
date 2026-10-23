package ai.resourcepack.ui.framework.pack;

import ai.resourcepack.ui.framework.render.Palette;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The core shaders the pack replaces, written once and emitted in each dialect the game has spoken:
 *
 * <ol>
 *   <li>{@code text.fsh} decodes hi-colour maps (every item-frame map is drawn with the text
 *       pipeline), and draws a seated player's own view and pointer in front of the world;
 *       {@code text.vsh} stretches that view to fill their window. Where a map is transparent (map
 *       colours 0-3) the world behind shows, and on a seated player's own view under the view's tint
 *       (the text pipeline blends: {@code BlendFunction.TRANSLUCENT}).</li>
 *   <li>{@code position_tex_color} hides the hotbar, hearts, crosshair and vignette, but only for a
 *       seated player.</li>
 *   <li>A post effect, {@code post_effect/creeper.json}, blurs the world round a blurred view's panel
 *       ({@link #overlayBlurChain}; 26.2 only).</li>
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

    /**
     * The red of the pointer a seated player's own client places, every frame, from where they look
     * (see {@link #clientPointerRgb}); green and blue are the view's columns and rows, as for
     * {@link #POINTER_RED}.
     */
    public static final int CLIENT_POINTER_RED = 253;

    /** The client-placed pointer's text colour on a view of [cols] × [rows] maps. */
    public static int clientPointerRgb(int cols, int rows) {
        return CLIENT_POINTER_RED << 16 | Math.min(255, cols) << 8 | Math.min(255, rows);
    }

    /**
     * How far ahead of the camera, along its flagged axis, the client-placed pointer's text sits for a
     * pointer speed of [pixelsPerDegree]: the shader reads the speed back from that distance.
     */
    public static double clientPointerDistance(double pixelsPerDegree) {
        return 0.5 + pixelsPerDegree / 512;
    }

    /** The fastest pointer {@link #clientPointerDistance} can carry. */
    public static final double CLIENT_POINTER_MAX_SPEED = 255;

    /**
     * A blurred view's signature (see {@code mcb_signature} in text.fsh and {@link #overlayBlurFsh}): the RGB of the
     * window's bottom-left pixel, and the blue of the fourth pixel along. Colours the world is all but certain never to
     * put in both places at once.
     */
    static final int SIGNATURE_0 = 0x5AC396, SIGNATURE_3 = 0x69;

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
            // Seated cameras sit at a flagged fraction of a block along one horizontal axis: 0 for x, 2 for
            // z, -1 when this camera is not seated.
            int mcb_flag_axis() {
                vec3 f = -CameraOffset;
                if (!mcb_grid(f.y)) return -1;
                if (mcb_grid(f.z) && mcb_flag(f.x) > 0.0) return 0;
                if (mcb_grid(f.x) && mcb_flag(f.z) > 0.0) return 2;
                return -1;
            }
            float mcb_seated_flag() { return mcb_flag_axis() >= 0 ? 1.0 : 0.0; }
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

                #if !defined(IS_GUI)
                // A point at normalised device coordinates [ndc], one block in front of the camera: in the
                // clip volume whatever the projection, at the same depth for every vertex (no perspective
                // warp across a quad). The fragment shader sets the real depth.
                vec4 mcb_flat(vec2 ndc) {
                    vec4 c = ProjMat * vec4(0.0, 0.0, -1.0, 1.0);
                    return vec4(ndc * c.w, c.z, c.w);
                }

                // The pointer the seated player's client places itself, on a view [size] pixels big. Its text
                // sits ahead of the camera on the flagged axis, towards the view, 0.5 + speed / 512 blocks away
                // (speed in pixels per degree of turn), square to that axis: so a vertex's offset across the axis
                // is the glyph's own, from its hot pixel, at 128 pixels to a block. The hot pixel is the view's
                // centre plus the speed times how far the camera has turned from that axis, held inside the
                // view; the server works the cursor out the same way from the rotations the client sends.
                vec2 mcb_client_pointer(vec2 size) {
                    vec3 axis = mcb_flag_axis() == 0 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 0.0, 1.0);
                    float along = dot(Position, axis);
                    vec3 ahead = axis * sign(along);
                    vec3 right = vec3(-ahead.z, 0.0, ahead.x);
                    float speed = (abs(along) - 0.5) * 512.0;
                    // ModelViewMat is the camera's rotation alone; its third row is minus the look direction.
                    vec3 look = normalize(-vec3(ModelViewMat[0][2], ModelViewMat[1][2], ModelViewMat[2][2]));
                    vec2 turn = degrees(vec2(atan(dot(look, right), dot(look, ahead)), asin(clamp(-look.y, -1.0, 1.0))));
                    vec2 hot = clamp(size * 0.5 + turn * speed, vec2(0.0), size - 1.0);
                    vec2 px = hot + vec2(dot(Position, right), -Position.y) * 128.0;
                    return vec2(px.x / size.x * 2.0 - 1.0, 1.0 - px.y / size.y * 2.0);
                }
                #endif

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
                        int mcbMap = mcb_screen_map();
                        if (mcbRgb.r == @PR@ && mcbRgb.g > 0 && mcbRgb.b > 0) {
                            mcbPointer = 1.0;
                            mcbGrid = vec2(mcbRgb.gb);
                            mcbTint = vec4(1.0, 1.0, 1.0, Color.a);
                        } else if (mcbRgb.r == @PC@ && mcbRgb.g > 0 && mcbRgb.b > 0) {
                            // The pointer this client places itself, from where the camera looks.
                            mcbPointer = 2.0;
                            mcbTint = vec4(1.0, 1.0, 1.0, Color.a);
                            gl_Position = mcb_flat(mcb_client_pointer(vec2(mcbRgb.gb) * 128.0));
                        } else if (mcbMap == 2 || mcbMap == 4) {
                            // Their own view's maps carry its columns and rows next to the marker.
                            mcbGrid = vec2(float(mcb_symbol(ivec2(2, 0))), float(mcb_symbol(ivec2(3, 0))));
                        } else if (mcbMap == 3) {
                            // A view whose pointer this client places: each map also carries its own column and
                            // row, and goes to its place in the window by those and its corner (the map quad's
                            // texture coordinates are exactly 0 and 1), wherever its frame is and wherever the
                            // camera looks.
                            vec2 mcbViewGrid = vec2(float(mcb_symbol(ivec2(2, 0))), float(mcb_symbol(ivec2(3, 0))));
                            vec2 mcbTile = vec2(float(mcb_symbol(ivec2(2, 1))), float(mcb_symbol(ivec2(3, 1))));
                            vec2 mcbAt = (mcbTile + UV0) / mcbViewGrid;
                            gl_Position = mcb_flat(vec2(mcbAt.x * 2.0 - 1.0, 1.0 - mcbAt.y * 2.0));
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
                """.replace("@PR@", String.valueOf(POINTER_RED)).replace("@PC@", String.valueOf(CLIENT_POINTER_RED)), d);
    }

    /**
     * Map colour to symbol, and which maps are ours: both text shaders need it, the vertex one to find a
     * seated player's own view (and its size), the fragment one to decode every screen.
     */
    private static String decoderCore(Palette palette) {
        SymbolHash hash = new SymbolHash(palette.symbolRgb);
        return """

                // ---- UI Engine hi-colour map decoder ----
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
                // 3: such a view whose pointer their client places, each map carrying its place (both the fourth).
                // 4: a view as 2 whose client blurs the world round its panel, each map carrying the panel (both the third).
                int mcb_screen_map() {
                    if (textureSize(Sampler0, 0) != ivec2(128, 128)
                        || mcb_fetch(ivec2(0, 0)) != @M0@ || mcb_fetch(ivec2(1, 0)) != @M1@) return 0;
                    ivec3 a = mcb_fetch(ivec2(0, 1));
                    ivec3 b = mcb_fetch(ivec2(1, 1));
                    if (a == @M2@ && b == @M3@) return 1;
                    if (a == @M3@ && b == @M2@) return 2;
                    if (a == @M3@ && b == @M3@) return 3;
                    if (a == @M2@ && b == @M2@) return 4;
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
                // + 1 blocks of the top row are the marker (and a view's size and tint): they show the next
                // one. A transparent block (map colours 0-3, alpha 0) adds no colour: alpha is how much of
                // the footprint the opaque blocks cover, so a picture's edge against the world is smooth.
                vec4 mcb_sample(vec2 t, vec2 fw, int info) {
                    vec2 halfFw = clamp(fw, vec2(0.02), vec2(2.0)) * 0.5;
                    vec2 lo = clamp(t - halfFw, vec2(0.0), vec2(128.0));
                    vec2 hi = clamp(t + halfFw, vec2(0.0), vec2(128.0));
                    ivec2 b0 = clamp(ivec2(floor(lo * 0.5)), ivec2(0), ivec2(63));
                    ivec2 b1 = clamp(ivec2(floor((hi - 0.0001) * 0.5)), ivec2(0), ivec2(63));
                    vec3 acc = vec3(0.0);
                    float total = 0.0;
                    float covered = 0.0;
                    for (int by = b0.y; by <= b1.y; by++) {
                        for (int bx = b0.x; bx <= b1.x; bx++) {
                            ivec2 base = ivec2(bx, by) * 2;
                            ivec2 src = (by == 0 && bx <= info) ? ivec2(2 * info + 2, 0) : base;
                            vec2 p0 = vec2(base);
                            vec2 ov0 = max(min(hi, p0 + 1.0) - max(lo, p0), 0.0);
                            vec2 ov1 = max(min(hi, p0 + 2.0) - max(lo, p0 + 1.0), 0.0);
                            vec4 w = vec4(ov0.x * ov0.y, ov1.x * ov0.y, ov0.x * ov1.y, ov1.x * ov1.y);
                            float ws = w.x + w.y + w.z + w.w;
                            total += ws;
                            if (texelFetch(Sampler0, src, 0).a < 0.5) continue;
                            int s00 = mcb_symbol(src);
                            int s10 = mcb_symbol(src + ivec2(1, 0));
                            int s01 = mcb_symbol(src + ivec2(0, 1));
                            int s11 = mcb_symbol(src + ivec2(1, 1));
                            vec4 luma = vec4(float(s00 / @DIGITS@), float(s10 / @DIGITS@), float(s01 / @DIGITS@), float(s11 / @DIGITS@)) / @LUMAMAX@;
                            vec2 chroma = vec2(float((s00 % @DIGITS@) * @DIGITS@ + (s10 % @DIGITS@) - 32),
                                               float((s01 % @DIGITS@) * @DIGITS@ + (s11 % @DIGITS@) - 32)) / 63.0;
                            acc += vec3(dot(w, luma), chroma * ws);
                            covered += ws;
                        }
                    }
                    if (covered <= 0.0) return vec4(0.0);
                    vec3 ycc = acc / covered;
                    return vec4(clamp(vec3(ycc.x + 1.402 * ycc.z, ycc.x - 0.344136 * ycc.y - 0.714136 * ycc.z, ycc.x + 1.772 * ycc.y), 0.0, 1.0),
                                covered / max(total, 1e-6));
                }

                // What someone's own view lays over the world where it is transparent (Palette.markTint): red,
                // green, blue and alpha in the 2x2 block after its size.
                vec4 mcb_tint() {
                    return vec4(float(mcb_symbol(ivec2(4, 0))), float(mcb_symbol(ivec2(5, 0))),
                                float(mcb_symbol(ivec2(4, 1))), float(mcb_symbol(ivec2(5, 1)))) / @SYMMAX@;
                }

                // One edge of a blurred view's panel in canvas pixels (Palette.markBlurred): two symbols, high and low.
                float mcb_edge(ivec2 p) { return float(mcb_symbol(p) * @SYMBOLS@ + mcb_symbol(p + ivec2(1, 0))); }

                // Where a blurred view's panel is, for the post effect that blurs the world round it (Shaders.overlayBlurFsh),
                // written as the window's four bottom-left pixels: a check, the panel's left and top edges, its right and
                // bottom ones (fractions of the view, 16 bits each), and a second check.
                vec3 mcb_signature(int i) {
                    vec2 size = vec2(float(mcb_symbol(ivec2(2, 0))), float(mcb_symbol(ivec2(3, 0)))) * 128.0;
                    vec4 edges = vec4(mcb_edge(ivec2(6, 0)), mcb_edge(ivec2(6, 1)), mcb_edge(ivec2(8, 0)), mcb_edge(ivec2(8, 1))) / size.xyxy;
                    ivec4 e = ivec4(round(clamp(edges, 0.0, 1.0) * 65535.0));
                    ivec3 c = i == 0 ? ivec3(@SIG0@)
                            : i == 1 ? ivec3(e.x >> 8, e.x & 255, e.y >> 8)
                            : i == 2 ? ivec3(e.y & 255, e.z >> 8, e.z & 255)
                            : ivec3(e.w >> 8, e.w & 255, @SIG3@);
                    return vec3(c) / 255.0;
                }
                // ---- end decoder ----

                bool mcb_in(vec2 p, vec2 a, vec2 b) { return p.x >= a.x && p.x <= b.x && p.y >= a.y && p.y <= b.y; }
                """
                .replace("@DIGITS@", String.valueOf(Palette.DIGITS))
                .replace("@LUMAMAX@", String.format(Locale.ROOT, "%.1f", (float) (Palette.LUMA_LEVELS - 1)))
                .replace("@SYMMAX@", String.format(Locale.ROOT, "%.1f", (float) (Palette.SYMBOLS - 1)))
                .replace("@SYMBOLS@", String.valueOf(Palette.SYMBOLS))
                .replace("@SIG0@", (SIGNATURE_0 >> 16) + "," + (SIGNATURE_0 >> 8 & 255) + "," + (SIGNATURE_0 & 255))
                .replace("@SIG3@", String.valueOf(SIGNATURE_3));

        String mainHead = """

                void main() {
                    // Derivatives before any branch or discard.
                    vec2 mcbFootprint = fwidth(texCoord0 * 128.0);
                    float mcbDepth = gl_FragCoord.z;
                #ifndef IS_GUI
                    // The client-placed pointer is as near as depth goes, in front of the picture and of everything.
                    if (mcbSeated > 0.5 && mcbPointer > 1.5) mcbDepth = mcbReversed > 0.5 ? 1.0 : 0.0;
                    else if (mcbSeated > 0.5 && mcbPointer > 0.5) mcbDepth = mcb_in_front(128.0);
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
                        // Someone's own view carries its size and tint in a second and third block (info 2), a blurred
                        // one its panel in a fourth and fifth (info 4).
                        vec4 mcbPicture = mcb_sample(texCoord0 * 128.0, mcbFootprint, mcbMap == 4 ? 4 : mcbMap >= 2 ? 2 : 0);
                        // Where the picture is transparent the world behind shows, on someone's own view under its
                        // tint: the picture over the tint over the world, as one colour blended over the world.
                        vec4 mcbTint = mcbMap >= 2 && mcbPicture.a < 1.0 ? mcb_tint() : vec4(0.0);
                        float mcbAlpha = 1.0 - (1.0 - mcbPicture.a) * (1.0 - mcbTint.a);
                        bool mcbSignature = false;
                #ifndef IS_GUI
                        mcbSignature = mcbMap == 4 && mcbSeated > 0.5 && gl_FragCoord.y < 1.0 && gl_FragCoord.x < 4.0;
                #endif
                        if (mcbAlpha < 0.002 && !mcbSignature) discard;
                        vec4 mcbColor = mcbSignature ? vec4(mcb_signature(int(gl_FragCoord.x)), 1.0)
                            : vec4((mcbPicture.rgb * mcbPicture.a + mcbTint.rgb * (mcbTint.a * (1.0 - mcbPicture.a))) / max(mcbAlpha, 0.002), mcbAlpha);
                #ifndef IS_GUI
                        if (mcbMap >= 2 && mcbSeated > 0.5) {
                            // A view the client places (3) is just behind its pointer, in front of all else: its
                            // frames are at the camera, so its own depth means nothing.
                            mcbDepth = mcbMap == 3 ? (mcbReversed > 0.5 ? 0.99999 : 0.00001) : mcb_in_front(64.0);
                            gl_FragDepth = mcbDepth;
                        }
                #endif
                """;

        String tail = d == Dialect.V26_3 ? """
                #ifdef OIT_ALPHA_ONLY
                        executeAlphaOnlyPhase(mcbDepth, mcbColor.a);
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

    // ---------------------------------------------------------------- the blur round a blurred view's panel

    /**
     * The post effect a blurred view's player sees: its camera is an invisible creeper ({@code Views}), and the game
     * runs {@code post_effect/creeper.json} over the whole frame for a creeper camera ({@code GameRenderer
     * .checkEntityPostEffect}), after the world and before the HUD and menus. This one is the menu blur's three
     * rounds of a horizontal and a vertical box blur, at the player's own menu blur setting, but only outside the
     * panel, which the view's own maps say where it is ({@code mcb_signature}). Without that signature (anyone
     * spectating a real creeper) it passes the picture through. 26.2 only: the pack ships it in the 26.2 overlay.
     */
    public static String overlayBlurChain() {
        StringBuilder passes = new StringBuilder();
        for (int round = 0; round < 3; round++) {
            for (int vertical = 0; vertical < 2; vertical++) {
                boolean last = round == 2 && vertical == 1;
                if (passes.length() > 0) passes.append(",\n");
                passes.append("""
                            {
                              "vertex_shader": "minecraft:core/screenquad",
                              "fragment_shader": "uiengine:post/overlay_blur",
                              "inputs": [{"sampler_name": "In", "target": "%s"}],
                              "output": "%s",
                              "uniforms": {"OverlayConfig": [
                                {"name": "BlurDir", "type": "vec2", "value": [%s]},
                                {"name": "Last", "type": "float", "value": %s}
                              ]}
                            }""".formatted(vertical == 0 ? "minecraft:main" : "swap", vertical == 0 ? "swap" : "minecraft:main",
                        vertical == 0 ? "1.0, 0.0" : "0.0, 1.0", last ? "1.0" : "0.0"));
            }
        }
        return "{\n  \"targets\": {\"swap\": {}},\n  \"passes\": [\n" + passes + "\n  ]\n}\n";
    }

    /** The fragment shader of {@link #overlayBlurChain}, in the 26.2 dialect. */
    public static String overlayBlurFsh() {
        return emit("""
                #version 330

                #moj_import <minecraft:globals.glsl>

                uniform sampler2D InSampler;

                layout(std140) uniform SamplerInfo {
                    vec2 OutSize;
                    vec2 InSize;
                };

                layout(std140) uniform OverlayConfig {
                    vec2 BlurDir;
                    float Last;
                };

                in vec2 texCoord;

                out vec4 fragColor;

                ivec3 ov_signature(int x) { return ivec3(round(texelFetch(InSampler, ivec2(x, 0), 0).rgb * 255.0)); }

                // Whether texel [p] is world to blur: outside the panel [lo, hi), and not one of the signature's four.
                bool ov_world(ivec2 p, vec2 lo, vec2 hi) {
                    if (p.y == 0 && p.x < 4) return false;
                    vec2 c = vec2(p) + 0.5;
                    return any(lessThan(c, lo)) || any(greaterThanEqual(c, hi));
                }

                void main() {
                    ivec2 at = ivec2(gl_FragCoord.xy);
                    vec4 here = texelFetch(InSampler, at, 0);
                    ivec3 s0 = ov_signature(0), s1 = ov_signature(1), s2 = ov_signature(2), s3 = ov_signature(3);
                    if (s0 != ivec3(@SIG0@) || s3.b != @SIG3@) {
                        fragColor = here;
                        return;
                    }
                    // The panel's left, top, right and bottom as fractions of the view, from its top-left; rows count up here.
                    vec4 e = vec4(s1.r * 256 + s1.g, s1.b * 256 + s2.r, s2.g * 256 + s2.b, s3.r * 256 + s3.g) / 65535.0;
                    vec2 lo = vec2(e.x, 1.0 - e.w) * InSize, hi = vec2(e.z, 1.0 - e.y) * InSize;
                    if (at.y == 0 && at.x < 4) {
                        // The signature itself: kept for the next pass, and the row above it once the last is done.
                        fragColor = Last > 0.5 ? texelFetch(InSampler, ivec2(at.x, 1), 0) : here;
                        return;
                    }
                    if (!ov_world(at, lo, hi)) {
                        fragColor = here;
                        return;
                    }
                    // A box blur along BlurDir of the world alone: the panel never bleeds into it.
                    ivec2 dir = ivec2(BlurDir);
                    ivec2 edge = ivec2(InSize) - 1;
                    vec4 sum = vec4(0.0);
                    float n = 0.0;
                    for (int k = -MenuBlurRadius; k <= MenuBlurRadius; k++) {
                        ivec2 p = clamp(at + dir * k, ivec2(0), edge);
                        if (!ov_world(p, lo, hi)) continue;
                        sum += texelFetch(InSampler, p, 0);
                        n += 1.0;
                    }
                    fragColor = n > 0.0 ? sum / n : here;
                }
                """.replace("@SIG0@", (SIGNATURE_0 >> 16) + ", " + (SIGNATURE_0 >> 8 & 255) + ", " + (SIGNATURE_0 & 255))
                .replace("@SIG3@", String.valueOf(SIGNATURE_3)), Dialect.V26_2);
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
