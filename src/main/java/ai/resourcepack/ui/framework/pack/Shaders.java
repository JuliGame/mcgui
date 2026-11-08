package ai.resourcepack.ui.framework.pack;

import ai.resourcepack.ui.framework.render.Palette;
import ai.resourcepack.ui.framework.render.SymbolHash;

import java.util.ArrayList;
import java.util.List;
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
 *       seated player, and for any flagged camera the game's in-wall, underwater and fire overlays.</li>
 *   <li>A post effect, {@code post_effect/entity_outline.json}, which the game runs whenever a glowing
 *       entity is drawn: besides the outlines, it keeps the world an overlay page's player saw when it
 *       opened and shows it, still, round the panel ({@link #overlayChain}).</li>
 *   <li>{@code rendertype_clouds.vsh} draws no clouds over that still world (before 26.3: 26.3 draws its clouds
 *       before the outline effect, so they are in the kept picture), nor 1.21.11's {@code particle.vsh} particles.</li>
 *   <li>A post effect, {@code post_effect/transparency.json} (before 26.3), the game's improved transparency, which
 *       blends translucent blocks, particles and the like in after the outline effect: it keeps the world whole round
 *       the panel again ({@link #transparencyChain}).</li>
 *   <li>A post effect, {@code post_effect/blur.json}, the game's menu blur, which first takes the pointer out
 *       of the picture, so it does not show under the pause menu beside the system's own cursor
 *       ({@link #menuBlurChain}).</li>
 * </ol>
 *
 * <p>"Seated" is read from {@code Globals.CameraOffset}: the engine puts a seated player's camera at a
 * block fraction of k/16 + 3/128 along the view's normal and 0 or 1/2 on the other two axes, or for an
 * overlay k/16 + 3/128 on all three, which no normal eye position produces. Everyone else sees vanilla.
 *
 * <p><b>26.3 rewrote the shader dialect</b> (explicit {@code layout(location)} on every varying,
 * {@code #include} for {@code #moj_import}, reordered uniform blocks, order-independent transparency
 * in the text shader). Sources here are in the 26.3 form; {@link #to262} derives the older one, and the
 * pack ships each in the overlay for its formats.
 *
 * <p><b>Before 26.2</b> one shader, {@code rendertype_text}, draws text in the GUI and in the world alike, where 26.2
 * compiles {@code text} once for each with {@code IS_GUI} defined or not; {@link #presplit} makes the one shader do
 * both, deciding by the projection as it runs. 1.21.11 reads the lightmap itself (26.1 added
 * {@code sample_lightmap.glsl}). 1.21.11 is the oldest: 1.21.9 and 1.21.10 have no {@code CameraOffset}, so no camera
 * there could be told seated.
 */
public final class Shaders {
    private Shaders() {}

    static {
        // text.fsh splits a symbol into luma and digit with >> 3 and & 7.
        if (Palette.DIGITS != 8) throw new AssertionError("text.fsh assumes 8 digits a symbol");
    }

    /**
     * The dialects the pack ships, each with the protocol version of the client it was checked in and the pack
     * formats its overlay covers (V26_3's are the pack's own: its files are the pack's root).
     */
    public enum Dialect {
        V1_21_11(774, 75, 83), V26_1(775, 84, 87), V26_2(776, 88, 96), V26_3(777, 97, 97);

        public final int protocol;
        public final int minFormat, maxFormat;

        Dialect(int protocol, int minFormat, int maxFormat) {
            this.protocol = protocol;
            this.minFormat = minFormat;
            this.maxFormat = maxFormat;
        }

        /** One {@code rendertype_text} for GUI and world text (before 26.2). */
        public boolean presplit() {
            return compareTo(V26_2) < 0;
        }

        /** The core text shader's name in this version. */
        public String textShader() {
            return presplit() ? "rendertype_text" : "text";
        }

        /** The overlay directory this dialect's files go in, or null for the pack's root. */
        public String overlay() {
            return this == V26_3 ? null : "uiengine_" + name().substring(1).toLowerCase(Locale.ROOT);
        }

        /**
         * The dialect a client of [protocol] reads, or null for a version nobody has seen the pack's post effects
         * run in: a newer one may load the pack but not the overlay's chain, and then its world would turn with
         * the pointer, so it gets an overlay with the server's pointer and the world live round it.
         */
        public static Dialect of(int protocol) {
            for (Dialect d : values()) if (d.protocol == protocol) return d;
            return null;
        }
    }

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
     * The colour of the text a view puts in front of its camera to tell a player whose shaders are not this pack's that
     * the view cannot be drawn: Iris with a shader pack draws world text (and maps) with the pack's own programs, so the
     * picture is never placed and the text shows. Where this pack's text.vsh runs, text of exactly this colour and
     * {@link #NOTICE_ALPHA} is never drawn, so nobody else sees it.
     */
    public static final int NOTICE_RGB = 0xFCE04A;

    /**
     * The notice's text opacity, its second mark: text of {@link #NOTICE_RGB} that is opaque (a hologram, a name, a step
     * of a gradient) is drawn as ever. 254 of 255 looks opaque where the notice does show.
     */
    public static final int NOTICE_ALPHA = 254;

    /**
     * The pointer font's alpha is stored at this many 255ths of what it is (rounded up, so a pixel that shows still
     * does), and text.fsh scales it back for the pointer: under the 0.1 alpha cut-off every other text shader uses
     * (Iris's with a shader pack among them), so a pointer that a shader pack draws as plain text in the world is not
     * seen.
     */
    public static final int POINTER_ALPHA = 24;

    /** How finely {@link #turnHint} gives a turn: 64 sectors of 5.625°. */
    public static final int TURN_SECTORS = 64;

    /**
     * A client-placed pointer's text opacity for a player turned [turn] degrees from their view's centre (128 plus its
     * sector of {@link #TURN_SECTORS}; the shader takes an opacity outside 128 to 191 as no hint, so the 255 of a
     * freshly spawned pointer is none). Opacity is the alpha of the text's vertices, the only per-entity value text
     * carries besides its colour, which already holds the pointer's red and its view's size.
     */
    public static int turnHint(double turn) {
        return 128 + Math.floorMod(Math.round(turn / 360 * TURN_SECTORS), TURN_SECTORS);
    }

    /** A hover layer's turn hint: 1 plus the quarter turn nearest [turn] (0 to 3, counted as {@link #turnHint}'s); 0 is none. */
    public static int turnQuarter(double turn) {
        return 1 + Math.floorMod(Math.round(turn / 90), 4);
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
     * A hover layer's map ({@code Palette.markLayer}) carries the view's pointer speed times this, as a whole number in
     * three symbols: the server rounds the view's speed to it first, so the shader and the server's cursor agree.
     */
    public static final int LAYER_SPEED_SCALE = 32768;

    /** The view's speed as a hover layer carries it: [pixelsPerDegree] rounded to {@link #LAYER_SPEED_SCALE}ths. */
    public static double layerSpeed(double pixelsPerDegree) {
        return Math.round(pixelsPerDegree * LAYER_SPEED_SCALE) / (double) LAYER_SPEED_SCALE;
    }

    /**
     * An overlay's signature (see {@code mcb_signature} in text.fsh and {@link #overlayChain}): the RGB of the
     * window's bottom-left pixel, and the fourth pixel along, red and green 0 and blue this. Colours the world is
     * all but certain never to put in both places at once. The second pixel is the view's tint, the third its
     * alpha and the {@code SIGNATURE_*} flags.
     */
    static final int SIGNATURE_0 = 0x5AC396, SIGNATURE_3 = 0x69;

    /** The tile column and row an overlay's signature map carries in place of its own (Palette.markSignature). */
    public static final int SIGNATURE_TILE = Palette.SYMBOLS - 1;

    /** Signature flags: blur the world round the panel; the world is kept (not taken afresh each frame); depth is reversed. */
    public static final int SIGNATURE_BLUR = 1, SIGNATURE_KEPT = 2, SIGNATURE_REVERSED = 4;

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
        if (d == Dialect.V26_3) return s;
        s = to262(s);
        if (d.presplit()) s = presplit(s);
        if (d.compareTo(Dialect.V26_1) < 0) {
            // No sample_lightmap.glsl yet: the lightmap read as its own shaders did.
            s = s.replace("#moj_import <minecraft:sample_lightmap.glsl>\n", "")
                    .replace("sample_lightmap(Sampler2, UV2)", "texelFetch(Sampler2, UV2 / 16, 0)");
        }
        return s;
    }

    private static final Pattern DEFINED = Pattern.compile("!?\\s*defined\\s*\\(\\s*(\\w+)\\s*\\)");

    /**
     * [src] (26.2's form) as the one text shader of a version before 26.2, which draws GUI text and world text alike: the
     * world's variant (no define set), with each {@code #ifdef IS_GUI} or {@code #ifndef IS_GUI} inside a function made
     * a test of the projection as it runs ({@code mcb_gui()}: a GUI draw is orthographic). Every other conditional is
     * decided here with nothing defined. Only the forms the sources use are understood; anything else throws.
     */
    static String presplit(String src) {
        record Frame(boolean runtime, boolean parent, boolean taking, boolean done) {}
        java.util.ArrayDeque<Frame> stack = new java.util.ArrayDeque<>();
        StringBuilder out = new StringBuilder();
        int depth = 0;
        for (String line : src.split("\n", -1)) {
            String t = line.strip();
            boolean active = stack.isEmpty() || stack.peek().taking();
            if (t.startsWith("#ifdef ") || t.startsWith("#ifndef ") || t.startsWith("#if ")) {
                boolean gui = t.equals("#ifdef IS_GUI") || t.equals("#ifndef IS_GUI");
                if (gui && depth > 0) {
                    stack.push(new Frame(true, active, active, true));
                    if (active) out.append(t.startsWith("#ifdef") ? "if (mcb_gui()) {" : "if (!mcb_gui()) {").append('\n');
                } else {
                    boolean cond = t.startsWith("#ifdef ") ? false : t.startsWith("#ifndef ") ? true : condition(t.substring(4));
                    stack.push(new Frame(false, active, active && cond, cond));
                }
            } else if (t.startsWith("#elif ")) {
                Frame f = stack.pop();
                if (f.runtime()) throw new IllegalArgumentException("#elif after a run-time IS_GUI test: " + t);
                boolean cond = condition(t.substring(6));
                stack.push(new Frame(false, f.parent(), f.parent() && !f.done() && cond, f.done() || cond));
            } else if (t.equals("#else")) {
                Frame f = stack.pop();
                if (f.runtime()) {
                    if (f.parent()) out.append("} else {\n");
                    stack.push(f);
                } else {
                    stack.push(new Frame(false, f.parent(), f.parent() && !f.done(), true));
                }
            } else if (t.equals("#endif")) {
                Frame f = stack.pop();
                if (f.runtime() && f.parent()) out.append("}\n");
            } else if (active) {
                out.append(line).append('\n');
                for (char c : t.toCharArray()) depth += c == '{' ? 1 : c == '}' ? -1 : 0;
            }
        }
        if (!stack.isEmpty()) throw new IllegalArgumentException("Unbalanced #if in a shader");
        String s = out.toString();
        if (s.endsWith("\n\n")) s = s.substring(0, s.length() - 1);
        if (!s.contains("mcb_gui()")) return s;
        s = s.replace("\nvoid main() {", "\nbool mcb_gui() { return ProjMat[3][3] == 1.0; }\n\nvoid main() {");
        // The fragment shader needs the projection for mcb_gui() too.
        if (!s.contains("projection.glsl") && !s.contains("uniform Projection")) {
            s = s.replace("#moj_import <minecraft:dynamictransforms.glsl>\n",
                    "#moj_import <minecraft:dynamictransforms.glsl>\n#moj_import <minecraft:projection.glsl>\n");
        }
        return s;
    }

    /** A {@code #if} condition with nothing defined: {@code defined(X)} and its negation, joined by && or ||. */
    private static boolean condition(String expr) {
        String e = expr.strip();
        if (e.contains("||")) {
            for (String part : e.split("\\|\\|")) if (condition(part)) return true;
            return false;
        }
        for (String part : e.split("&&")) {
            var m = DEFINED.matcher(part.strip());
            if (!m.matches()) throw new IllegalArgumentException("A shader condition presplit cannot read: " + expr);
            if (!part.strip().startsWith("!")) return false;
        }
        return true;
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
            // Seated cameras sit at a flagged fraction of a block along one horizontal axis and on the half-block
            // grid on the other two: 0 for x, 2 for z. An overlay's camera is the player's own eye with all three
            // flagged (each moved at most 1/32 of a block): 1. -1 when this camera is not seated.
            int mcb_flag_axis() {
                vec3 f = -CameraOffset;
                if (mcb_flag(f.x) > 0.0 && mcb_flag(f.y) > 0.0 && mcb_flag(f.z) > 0.0) return 1;
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

    // ---------------------------------------------------------------- merge markers

    /**
     * The marker lines plugin-common's {@code ShaderMerge} reads (Studio writes the same): every addition to vanilla's
     * text shader is between them, so another plugin's text shader (BetterHud's, HUDEngine's, ItemsAdder's text effects)
     * can be given the same additions and both draw. The format is versioned by the {@code v1} on every begin line;
     * changing what a block means is a {@code v2}, never an edit, as packs built with v1 are on servers.
     */
    static final String MERGE_MARKER = "// rpai-merge:";

    /** What vanilla's {@code main} becomes in our text shaders, so ours can call it first. */
    static final String VERTEX_BASE = "mcgui_text_vertex", FRAGMENT_BASE = "mcgui_text_fragment";

    private static String mergeBlock(String name, String body) {
        String b = body.isEmpty() || body.endsWith("\n") ? body : body + "\n";
        return MERGE_MARKER + "begin " + name + " v1\n" + b + MERGE_MARKER + "end " + name + "\n";
    }

    /**
     * Vanilla's own top-level imports and declarations, every one outside any {@code #if}, commented out: what our code
     * may lean on that vanilla's file supplies, for a merge into a file that lacks one (Studio's {@code vanillaInterface}).
     */
    static String vanillaInterface(String src) {
        Pattern declaration = Pattern.compile("^(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:flat|uniform|in|out|const)\\s+)+\\w+\\s+\\w+\\s*(?:\\[[^\\]]*\\])?\\s*;$");
        StringBuilder out = new StringBuilder();
        int depth = 0, conditions = 0;
        for (String raw : src.split("\n")) {
            String line = raw.replaceAll("//.*$", "").strip();
            if (line.matches("^#\\s*(if|ifdef|ifndef)\\b.*")) conditions++;
            else if (line.matches("^#\\s*endif\\b.*")) conditions = Math.max(0, conditions - 1);
            else if (depth == 0 && conditions == 0 && (line.matches("^#\\s*(?:moj_import|include)\\b.*") || declaration.matcher(line).matches())) {
                out.append("// ").append(line).append('\n');
            }
            for (char c : line.toCharArray()) depth += c == '{' ? 1 : c == '}' ? -1 : 0;
        }
        return out.toString();
    }

    /**
     * Vanilla's file with its {@code main} renamed [base] and ours after it: [declarations] (before the renamed main,
     * where a merge puts them in another plugin's file), then vanilla's interface and [epilogue], which holds our
     * functions and the new {@code main} that calls [base].
     */
    private static String marked(String vanilla, String stage, String base, String declarations, String epilogue) {
        int at = vanilla.indexOf("\nvoid main()");
        if (at < 0) throw new IllegalStateException("vanilla's " + stage + " shader has no main");
        return vanilla.substring(0, at + 1)
                + mergeBlock(stage + "-declarations", declarations)
                + vanilla.substring(at + 1).replaceFirst("void main\\(\\)", "void " + base + "()")
                + mergeBlock(stage + "-vanilla", vanillaInterface(vanilla))
                + mergeBlock(stage + "-epilogue", MERGE_MARKER + "renames main " + base + "\n" + epilogue);
    }

    private static String include(Dialect d, String file) {
        return (d == Dialect.V26_3 ? "#include" : "#moj_import") + " <minecraft:" + file + ">\n";
    }

    // ---------------------------------------------------------------- vanilla's own files, per version

    /** Vanilla's text vertex shader for a dialect, as the client jar has it. */
    static String vanillaTextVsh(Dialect d) {
        return switch (d) {
            case V26_3 -> """
                    #version 330
                    #extension GL_ARB_separate_shader_objects : require

                    #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                    #include <minecraft:fog.glsl>
                    #include <minecraft:sample_lightmap.glsl>
                    #endif

                    #include <minecraft:dynamictransforms.glsl>
                    #include <minecraft:projection.glsl>

                    layout(location = 0) in vec3 Position;
                    layout(location = 1) in vec4 Color;
                    layout(location = 2) in vec2 UV0;
                    #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                    layout(location = 3) in ivec2 UV2;
                    #endif

                    #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                    uniform sampler2D Sampler2;
                    layout(location = 0) out float sphericalVertexDistance;
                    layout(location = 1) out float cylindricalVertexDistance;
                    #endif

                    layout(location = 2) out vec4 vertexColor;
                    layout(location = 3) out vec2 texCoord0;

                    void main() {
                        gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

                    #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                        sphericalVertexDistance = fog_spherical_distance(Position);
                        cylindricalVertexDistance = fog_cylindrical_distance(Position);
                        vertexColor = Color * sample_lightmap(Sampler2, UV2);
                    #else
                        vertexColor = Color;
                    #endif
                        texCoord0 = UV0;
                    }
                    """;
            case V26_2 -> """
                    #version 330

                    #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                    #moj_import <minecraft:fog.glsl>
                    #moj_import <minecraft:sample_lightmap.glsl>
                    #endif

                    #moj_import <minecraft:dynamictransforms.glsl>
                    #moj_import <minecraft:projection.glsl>

                    in vec3 Position;
                    in vec4 Color;
                    in vec2 UV0;
                    #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                    in ivec2 UV2;
                    #endif

                    #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                    uniform sampler2D Sampler2;
                    out float sphericalVertexDistance;
                    out float cylindricalVertexDistance;
                    #endif

                    out vec4 vertexColor;
                    out vec2 texCoord0;

                    void main() {
                        gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

                    #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                        sphericalVertexDistance = fog_spherical_distance(Position);
                        cylindricalVertexDistance = fog_cylindrical_distance(Position);
                        vertexColor = Color * sample_lightmap(Sampler2, UV2);
                    #else
                        vertexColor = Color;
                    #endif
                        texCoord0 = UV0;
                    }
                    """;
            case V26_1, V1_21_11 -> """
                    #version 330

                    #moj_import <minecraft:fog.glsl>
                    #moj_import <minecraft:dynamictransforms.glsl>
                    #moj_import <minecraft:projection.glsl>
                    """ + (d == Dialect.V26_1 ? "#moj_import <minecraft:sample_lightmap.glsl>\n" : "") + """

                    in vec3 Position;
                    in vec4 Color;
                    in vec2 UV0;
                    in ivec2 UV2;

                    uniform sampler2D Sampler2;

                    out float sphericalVertexDistance;
                    out float cylindricalVertexDistance;
                    out vec4 vertexColor;
                    out vec2 texCoord0;

                    void main() {
                        gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

                        sphericalVertexDistance = fog_spherical_distance(Position);
                        cylindricalVertexDistance = fog_cylindrical_distance(Position);
                    """ + (d == Dialect.V26_1 ? "    vertexColor = Color * sample_lightmap(Sampler2, UV2);\n"
                    : "    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);\n") + """
                        texCoord0 = UV0;
                    }
                    """;
        };
    }

    /** Vanilla's text fragment shader for a dialect, as the client jar has it. */
    static String vanillaTextFsh(Dialect d) {
        return switch (d) {
            case V26_3 -> """
                    #version 330
                    #extension GL_ARB_separate_shader_objects : require

                    #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                    #include <minecraft:fog.glsl>
                    #endif

                    #include <minecraft:dynamictransforms.glsl>
                    #include <minecraft:oit.glsl>

                    uniform sampler2D Sampler0;

                    #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                    layout(location = 0) in float sphericalVertexDistance;
                    layout(location = 1) in float cylindricalVertexDistance;
                    #endif

                    layout(location = 2) in vec4 vertexColor;
                    layout(location = 3) in vec2 texCoord0;

                    #ifndef OIT_ALPHA_ONLY
                    layout(location = 0) out vec4 fragColor;
                    #endif

                    vec4 calculateFinalColor(vec4 color) {
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

                        return color;
                    }

                    void main() {
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
                        executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
                        #else
                        fragColor = calculateFinalColor(color);
                        #endif
                    }
                    """;
            case V26_2 -> """
                    #version 330

                    #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                    #moj_import <minecraft:fog.glsl>
                    #endif

                    #moj_import <minecraft:dynamictransforms.glsl>

                    uniform sampler2D Sampler0;

                    #if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
                    in float sphericalVertexDistance;
                    in float cylindricalVertexDistance;
                    #endif

                    in vec4 vertexColor;
                    in vec2 texCoord0;

                    out vec4 fragColor;

                    void main() {
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
            case V26_1, V1_21_11 -> """
                    #version 330

                    #moj_import <minecraft:fog.glsl>
                    #moj_import <minecraft:dynamictransforms.glsl>

                    uniform sampler2D Sampler0;

                    in float sphericalVertexDistance;
                    in float cylindricalVertexDistance;
                    in vec4 vertexColor;
                    in vec2 texCoord0;

                    out vec4 fragColor;

                    void main() {
                        vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
                        if (color.a < 0.1) {
                            discard;
                        }
                        fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
                    }
                    """;
        };
    }

    /** Vanilla's {@code position_tex_color.vsh}: it cannot import (it is used during startup), so it inlines its blocks. */
    static String vanillaPositionTexColorVsh(Dialect d) {
        boolean v263 = d == Dialect.V26_3;
        return "#version 330\n" + (v263 ? "#extension GL_ARB_separate_shader_objects : require\n" : "") + "\n"
                + "// Can't moj_import in things used during startup, when resource packs don't exist.\n"
                + "// This is a copy of dynamicimports.glsl and projection.glsl\n"
                + dynamicTransforms(d) + PROJECTION + "\n"
                + (v263 ? """
                        layout(location = 0) in vec3 Position;
                        layout(location = 1) in vec2 UV0;
                        layout(location = 2) in vec4 Color;

                        layout(location = 0) out vec2 texCoord0;
                        layout(location = 1) out vec4 vertexColor;
                        """ : """
                        in vec3 Position;
                        in vec2 UV0;
                        in vec4 Color;

                        out vec2 texCoord0;
                        out vec4 vertexColor;
                        """) + """

                void main() {
                    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

                    texCoord0 = UV0;
                    vertexColor = Color;
                }
                """;
    }

    // ---------------------------------------------------------------- position_tex_color

    /**
     * {@code position_tex_color}, both halves, which always ship together: a pack that brings its own pair (Dialog
     * Engine's drawn dialogs read a varying of their own) then never meets one half of ours, so the two always link.
     * The HUD is hidden per pixel in the fragment shader: hidden per vertex, the alpha fades across any quad that has
     * a corner in the hidden area (a menu's buttons round the crosshair), and the hotbar stayed on 26.3.
     */
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
                    // The game's screen effects (ScreenEffectRenderer: the inside-a-block, underwater and fire overlays) are
                    // the only draws here in perspective within two blocks of the eye. They follow the player, not the
                    // camera, and a view's player can be held inside a wall (Views: the seat behind a camera with no room
                    // round it): on a flagged camera they would cover the view, so nothing of them is drawn.
                    if (ProjMat[3][3] != 1.0 && mcb_flag_axis() >= 0 && length((ModelViewMat * vec4(Position, 1.0)).xyz) < 2.0) {
                        gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
                    }
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
                        ivec2 mcbTexSize = textureSize(Sampler0, 0);
                        // The vignette is the only standalone 256x256 texture the HUD draws.
                        if (mcbTexSize == ivec2(256, 256)) discard;
                        // Atlases and the game's HUD textures are powers of two. A picture drawn into a target first
                        // is the size of what it shows: a minimap mod's (Xaero's picture-in-picture, JourneyMap's
                        // window-sized layer) and the game's own pictures-in-picture.
                        if ((mcbTexSize.x & (mcbTexSize.x - 1)) != 0 || (mcbTexSize.y & (mcbTexSize.y - 1)) != 0) discard;
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

    /** A shader written for its dialect already: only the lines are trimmed, as {@link #emit} does. */
    private static String emitAsIs(String src) {
        return src.lines().map(String::strip).reduce((a, b) -> a + "\n" + b).orElse("") + "\n";
    }

    // ---------------------------------------------------------------- text

    /**
     * What {@code text.vsh} tells {@code text.fsh}, it tells in vanilla's own {@code vertexColor}, in values vanilla never
     * gives it: text.vsh writes nothing vanilla's does not, so our fragment shader links with anybody's vertex shader and
     * our vertex shader with anybody's fragment shader. A pack whose two halves do not link is dropped whole by the client
     * ("Failed to reload resources"), which is what another plugin's text.vsh beside ours used to do (HUDEngine's, merged
     * with MC GUI's by Nexo). Every value is the same at all four vertices of its quad, so interpolation hands it on intact;
     * each field is kept well under 2^17, where a float's error is far below the 0.5 that rounding forgives.
     *
     * <ul>
     *   <li>alpha from {@link #ENCODED_POINTER} to below 48: a seated player's pointer, 16 times its kind (1 the server's,
     *   2 the client's), plus 8 when depth is reversed, plus its opacity; red, green and blue its light.</li>
     *   <li>alpha from {@link #ENCODED_VIEW}: a map of someone's own view. Red: its kind (3, or 5 an overlay's signature, 6
     *   an overlay's panel, 7 a hover layer), 8 for its fill (mcb_fill_right), 16 times a hover layer's priority plus one
     *   (0: not shown), 1024 when depth is reversed. Green: where it starts in Sampler0, in maps, x plus 64 times y.
     *   Blue: its tint's first two symbols, 256 times the first plus the second; alpha past {@link #ENCODED_VIEW} the
     *   other two.</li>
     * </ul>
     *
     * A map on a wall carries nothing: text.fsh finds those itself, so walls draw under anybody's text.vsh.
     */
    static final float ENCODED_POINTER = 16f, ENCODED_VIEW = 65536f;

    /**
     * {@code text.vsh}: vanilla's, then ours ({@link #marked}), which places a seated player's own view and pointer in
     * their window, hides what must not show while seated, and hands text.fsh the rest in {@code vertexColor}
     * ({@link #ENCODED_VIEW}).
     */
    public static String textVsh(Dialect d, Palette palette) {
        String declarations = include(d, "globals.glsl") + "uniform sampler2D Sampler0;\n";
        declarations = MERGE_MARKER + "provides CameraBlockPos GlintAlpha CameraOffset GameTime ScreenSize MenuBlurRadius UseRgss\n" + declarations;
        String gui = d.presplit() ? "    if (mcb_gui()) {\n" : "#ifdef IS_GUI\n";
        String world = d.presplit() ? "    } else {\n" : "#else\n";
        String end = d.presplit() ? "    }\n" : "#endif\n";
        String epilogue = helpers(VERTEX_HELPERS) + FRAGMENT_HELPERS + decoderCore(palette)
                + (d.presplit() ? "\nbool mcb_gui() { return ProjMat[3][3] == 1.0; }\n" : "")
                + VERTEX_VIEW.replace("@GRAY@", d.presplit() ? "" : "#ifndef IS_GRAYSCALE\n")
                        .replace("@ENDGRAY@", d.presplit() ? "" : "#endif\n")
                        .replace("@SEE@", d.presplit() ? "" : "#ifdef IS_SEE_THROUGH\n")
                        .replace("@ENDSEE@", d.presplit() ? "" : "#endif\n")
                        // Before 26.2 see-through text has shaders of its own (seeThroughVsh), never this one.
                        .replace("@SEEBODY@", d.presplit() ? "" : "    if (mcbSeated > 0.5 && mcbPointer == 0) gl_Position = vec4(0.0, 0.0, 2.0, 1.0);\n")
                + "\nvoid main() {\n    " + VERTEX_BASE + "();\n" + gui + "    mcb_gui_vertex();\n" + world + "    mcb_world_vertex();\n" + end + "}\n";
        epilogue = epilogue.replace("@PR@", String.valueOf(POINTER_RED)).replace("@PC@", String.valueOf(CLIENT_POINTER_RED))
                .replace("@NOTICE@", (NOTICE_RGB >> 16) + ", " + (NOTICE_RGB >> 8 & 255) + ", " + (NOTICE_RGB & 255) + ", " + NOTICE_ALPHA)
                .replace("@SIGTILE@", SIGNATURE_TILE + ".0")
                .replace("@SYMBOLS@", String.valueOf(Palette.SYMBOLS))
                .replace("@SECTORS@", String.valueOf(TURN_SECTORS))
                .replace("@LAYERSPEED@", String.format(Locale.ROOT, "%.1f", (double) LAYER_SPEED_SCALE))
                .replace("@EPOINTER@", String.format(Locale.ROOT, "%.1f", ENCODED_POINTER))
                .replace("@EVIEW@", String.format(Locale.ROOT, "%.1f", ENCODED_VIEW));
        return emitAsIs(marked(vanillaTextVsh(d), "vertex", VERTEX_BASE, declarations, epilogue));
    }

    /** text.vsh's own part, after vanilla's main has run: everything a seated player's view and pointer need. */
    private static final String VERTEX_VIEW = """

            // A point at normalised device coordinates [ndc], one block in front of the camera: in the
            // clip volume whatever the projection, at the same depth for every vertex (no perspective
            // warp across a quad). The fragment shader sets the real depth.
            vec4 mcb_flat(vec2 ndc) {
                vec4 c = ProjMat * vec4(0.0, 0.0, -1.0, 1.0);
                return vec4(ndc * c.w, c.z, c.w);
            }

            // Where the pointer the seated player's client places itself is on a view [size] pixels big: the
            // view's centre plus [speed] (pixels per degree of turn) times how far the camera has turned from
            // [ahead] (the axis towards the view), held inside the view. The server works the cursor out the
            // same way from the rotations the client sends. [hint] is the player's own turn from [ahead] in degrees
            // as the server last heard it (Views.sendTurnHint), or below -360 for none: F5's view from the front
            // points the camera exactly the other way to the player (its yaw half round, its pitch negated), which
            // the camera alone cannot tell from the player having turned round. The player's own look is whichever
            // of the two is within a quarter turn of the hint; a hint that far out would take a whole quarter turn
            // inside one round trip, and the server turns the player back at the picture's edge.
            vec2 mcb_hot(vec2 size, vec3 ahead, float speed, float hint) {
                vec3 right = vec3(-ahead.z, 0.0, ahead.x);
                // ModelViewMat is the camera's rotation alone; its third row is minus the look direction.
                vec3 look = normalize(-vec3(ModelViewMat[0][2], ModelViewMat[1][2], ModelViewMat[2][2]));
                if (hint > -360.0) {
                    float h = radians(hint);
                    if (dot(look, ahead * cos(h) + right * sin(h)) < 0.0) look = -look;
                }
                vec2 turn = degrees(vec2(atan(dot(look, right), dot(look, ahead)), asin(clamp(-look.y, -1.0, 1.0))));
                return clamp(size * 0.5 + turn * speed, vec2(0.0), size - 1.0);
            }

            // The pointer the seated player's client places itself, on a view [size] pixels big. Its text
            // sits ahead of the camera on the flagged axis, towards the view, 0.5 + speed / 512 blocks away
            // (speed in pixels per degree of turn), square to that axis: so a vertex's offset across the axis
            // is the glyph's own, from its hot pixel (mcb_hot), at 128 pixels to a block.
            // On an overlay (flag 1) every axis is flagged: the text's own offset says which is ahead, as the glyph
            // is far smaller than its distance.
            vec2 mcb_client_pointer(vec2 size) {
                int flagAxis = mcb_flag_axis();
                bool alongX = flagAxis == 0 || (flagAxis == 1 && abs(Position.x) > abs(Position.z));
                vec3 axis = alongX ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 0.0, 1.0);
                float along = dot(Position, axis);
                vec3 ahead = axis * sign(along);
                vec3 right = vec3(-ahead.z, 0.0, ahead.x);
                // Its text's opacity is the hint (Shaders.turnHint): 128 plus the sector, anything else none.
                int sector = int(round(Color.a * 255.0)) - 128;
                float hint = sector >= 0 && sector < @SECTORS@ ? float(sector) * (360.0 / float(@SECTORS@)) : -1000.0;
                vec2 hot = mcb_hot(size, ahead, (abs(along) - 0.5) * 512.0, hint);
                vec2 px = hot + vec2(dot(Position, right), -Position.y) * 128.0;
                return vec2(px.x / size.x * 2.0 - 1.0, 1.0 - px.y / size.y * 2.0);
            }

            // A hover layer's rectangle in canvas pixels, [left, right) x [top, bottom), from the four 2x2 blocks'
            // worth of symbols from x = [at] (Palette.markLayer): each edge two symbols, high then low.
            float mcb_layer_edge(int x, int y) { return float(mcb_symbol(ivec2(x, y)) * @SYMBOLS@ + mcb_symbol(ivec2(x + 1, y))); }
            vec4 mcb_layer_rect(int at) { return vec4(mcb_layer_edge(at, 0), mcb_layer_edge(at, 1), mcb_layer_edge(at + 2, 0), mcb_layer_edge(at + 2, 1)); }
            bool mcb_layer_in(vec2 p, vec4 r) { return p.x >= r.x && p.y >= r.y && p.x < r.z && p.y < r.w; }

            // Whether a hover layer shows now: the client's pointer, worked out as the pointer's own from the speed,
            // axis and side the layer carries ([flags]: 2 for z, 1 for facing it from the positive side), is inside
            // its include rectangle and outside its exclude one.
            bool mcb_layer_on(vec2 size, int flags) {
                float speed = float((mcb_symbol(ivec2(4, 0)) * @SYMBOLS@ + mcb_symbol(ivec2(5, 0))) * @SYMBOLS@ + mcb_symbol(ivec2(4, 1))) / @LAYERSPEED@;
                vec3 axis = (flags & 2) == 0 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 0.0, 1.0);
                // The quarter-turn hint (Palette.markLayer, Shaders.turnQuarter): 0 for none.
                int quarter = mcb_symbol(ivec2(14, 1));
                vec2 hot = mcb_hot(size, (flags & 1) == 1 ? -axis : axis, speed, quarter > 0 ? float(quarter - 1) * 90.0 : -1000.0);
                return mcb_layer_in(hot, mcb_layer_rect(6)) && !mcb_layer_in(hot, mcb_layer_rect(10));
            }

            // Which of our maps this vertex's quad shows (mcb_screen_map), with mcbBase set to where that map starts in
            // Sampler0 and mcbUv to which of its corners this vertex is. ImmediatelyFast (a client mod) draws every
            // map from one big texture by default ("map_atlas_generation"), each 128 x 128 square of it a map, the
            // quad's texture coordinates that square's: a corner there is a corner of up to four maps. A view's maps
            // all hang in the camera's block, on its floor or ceiling (MapWall.Stack), so which corner of the block a
            // vertex is says which of its map it is: u runs along x on both, v along z on the floor and against it
            // on the ceiling (ItemFrameRenderer, rotation 0). Any other map of ours in such a texture (a wall's)
            // is -1: drawn where it hangs, it needs nothing from here, and text.fsh finds it pixel by pixel.
            vec2 mcbUv;
            int mcb_locate() {
                mcbUv = UV0;
                int textureKind = mcb_texture();
                if (textureKind != 2) return textureKind == 1 ? mcb_screen_map() : 0;
                ivec2 size = textureSize(Sampler0, 0);
                vec2 at = UV0 * vec2(size) / 128.0, k = round(at);
                if (any(greaterThan(abs(at - k), vec2(0.001)))) return 0;
                vec3 rel = Position - CameraOffset;
                if (mcb_flag_axis() >= 0 && all(greaterThan(rel, vec3(-0.01))) && all(lessThan(rel, vec3(1.01)))) {
                    vec2 corner = vec2(rel.x > 0.5 ? 1.0 : 0.0, (rel.y < 0.5) == (rel.z > 0.5) ? 1.0 : 0.0);
                    mcbBase = ivec2(k - corner) * 128;
                    if (mcb_in_texture(mcbBase, size)) {
                        int kind = mcb_screen_map();
                        if (kind >= 3) {
                            mcbUv = corner;
                            return kind;
                        }
                    }
                }
                mcbBase = ivec2(0);
                return 0;
            }

            // The tint's four symbols (Palette.markTint): red, green, blue and alpha in the 2x2 block after the view's size.
            ivec4 mcb_tint_symbols() {
                return ivec4(mcb_symbol(ivec2(4, 0)), mcb_symbol(ivec2(5, 0)), mcb_symbol(ivec2(4, 1)), mcb_symbol(ivec2(5, 1)));
            }

            // GUI text: the XP level over the hotbar is hidden while seated, like the rest of the HUD (position_tex_color).
            void mcb_gui_vertex() {
                if (mcb_seated_now() > 0.5) {
                    vec2 mcbPos = (ModelViewMat * vec4(Position, 1.0)).xy;
                    vec2 mcbSize = mcb_gui_size();
                    float cx = floor(mcbSize.x / 2.0);
                    if (mcb_in(mcbPos, vec2(cx - 40.0, mcbSize.y - 40.0), vec2(cx + 40.0, mcbSize.y - 22.0))) vertexColor.a = 0.0;
                }
            }

            void mcb_world_vertex() {
                float mcbSeated = mcb_seated_flag();
                bool mcbRev = mcb_reversed() > 0.5;
                int mcbMap = 0;
            @GRAY@    mcbMap = mcb_locate();
            @ENDGRAY@    int mcbKind = mcbMap;
                int mcbPointer = 0;
                int mcbPriority = 0;
                if (mcbSeated > 0.5) {
                    ivec3 mcbRgb = ivec3(round(Color.rgb * 255.0));
                    vec2 mcbGrid = vec2(0.0);
                    if (mcbRgb.r == @PR@ && mcbRgb.g > 0 && mcbRgb.b > 0) {
                        // The pointer: text in the pointer's red, its view's columns and rows in green and blue. Drawn white.
                        mcbPointer = 1;
                        mcbGrid = vec2(mcbRgb.gb);
                        // It lies ahead of the camera, a level mannequin (Views.spawnCamera). Behind it, F5's view
                        // from the front has turned that camera exactly half round about the vertical: the pointer is
                        // turned with it, which puts it where the camera facing the picture would see it.
                        vec3 mcbLook = -vec3(ModelViewMat[0][2], ModelViewMat[1][2], ModelViewMat[2][2]);
                        if (dot(Position, mcbLook) < 0.0) gl_Position = ProjMat * ModelViewMat * vec4(-Position.x, Position.y, -Position.z, 1.0);
                    } else if (mcbRgb.r == @PC@ && mcbRgb.g > 0 && mcbRgb.b > 0) {
                        // The pointer this client places itself, from where the camera looks.
                        mcbPointer = 2;
                        gl_Position = mcb_flat(mcb_client_pointer(vec2(mcbRgb.gb) * 128.0));
                    } else if (mcbMap == 3) {
                        // Their own view: each map carries its view's columns and rows and its own column and
                        // row, and goes to its place in the window by those and its corner (the map quad's
                        // texture coordinates are exactly 0 and 1), wherever its frame is and wherever the
                        // camera looks. An overlay sends only the maps its panel covers, so nothing is drawn
                        // round them.
                        vec2 mcbViewGrid = vec2(float(mcb_symbol(ivec2(2, 0))), float(mcb_symbol(ivec2(3, 0))));
                        vec2 mcbTile = vec2(float(mcb_symbol(ivec2(2, 1))), float(mcb_symbol(ivec2(3, 1))));
                        if (mcbTile == vec2(@SIGTILE@)) {
                            // An overlay's signature map (Palette.markSignature): over the window's four
                            // bottom-left pixels, which text.fsh writes for the post effect to read.
                            mcbKind = 5;
                            gl_Position = mcb_flat(vec2(-1.0) + vec2(mcbUv.x * 8.0, (1.0 - mcbUv.y) * 2.0) / ScreenSize);
                        } else {
                            if (mcb_flag_axis() == 1) mcbKind = 6;
                            vec2 mcbAt = (mcbTile + mcbUv) / mcbViewGrid;
                            gl_Position = mcb_flat(vec2(mcbAt.x * 2.0 - 1.0, 1.0 - mcbAt.y * 2.0));
                        }
                    } else if (mcbMap == 7) {
                        // A hover layer (Palette.markLayer): placed by the canvas pixel its third row starts at, 128
                        // canvas pixels to its side, but only while this client's pointer is over its element, and
                        // then with its priority plus one, for text.fsh's depth. Otherwise it goes behind the far
                        // plane, below.
                        int mcbFlags = mcb_symbol(ivec2(5, 1));
                        vec2 mcbViewGrid = vec2(float(mcb_symbol(ivec2(2, 0))), float(mcb_symbol(ivec2(3, 0))));
                        if (mcb_layer_on(mcbViewGrid * 128.0, mcbFlags)) {
                            mcbPriority = (mcbFlags >> 2) + 1;
                            vec2 mcbOrigin = vec2(mcb_layer_edge(2, 1), mcb_layer_edge(14, 0) - 2.0);
                            vec2 mcbAt = (mcbOrigin + mcbUv * 128.0) / (mcbViewGrid * 128.0);
                            gl_Position = mcb_flat(vec2(mcbAt.x * 2.0 - 1.0, 1.0 - mcbAt.y * 2.0));
                        }
                    }
                    // Both fill the window whatever its shape and whatever the player's FOV. The picture is centred
                    // on the camera's axis and square to it, so how far a vertex is from that axis, in blocks, as a
                    // fraction of half the picture's size, is where it goes in the window.
                    if (mcbGrid.x > 0.0 && mcbGrid.y > 0.0) {
                        vec2 mcbBlocks = gl_Position.xy / vec2(ProjMat[0][0], ProjMat[1][1]);
                        gl_Position.xy = mcbBlocks / (mcbGrid * 0.5) * gl_Position.w;
                    }
                }
                // A hover layer not shown, or not seated: every vertex behind the far plane, so it draws no pixel.
                if (mcbMap == 7 && mcbPriority == 0) gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
                // A view's own maps (3 and up) show only to its seated camera. They lie on the floor or ceiling of
                // the camera's block (MapWall.Stack), so a camera not flagged (the frames there a moment before the
                // eye is, or a moment after it is put back) drew them where they lie: pieces of the picture, huge,
                // round the camera.
                if (mcbMap >= 3 && mcbSeated < 0.5) gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
                // A view's notice for players whose shaders replace these (Shaders.NOTICE_RGB, NOTICE_ALPHA): never drawn here.
                if (ivec4(round(Color * 255.0)) == ivec4(@NOTICE@)) gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
            @SEE@    // See-through text (text displays with see_through, name tags' faint pass) is drawn with no depth
                // test, so over a seated camera's view too, and an overlay keeps what lies on its panel as its own:
                // there it moved with the pointer. None for a seated camera (its pointer is not see-through).
            @SEEBODY@@ENDSEE@    if (mcbPointer > 0) {
                    // Drawn white under whatever light the vertex shader before ours gave the text (its red over the
                    // text's own), at the server pointer's opacity; the client pointer's opacity is its turn hint.
                    float mcbLight = clamp(vertexColor.r / max(Color.r, 0.004), 0.0, 1.0);
                    float mcbAlpha = mcbPointer == 1 ? Color.a : 1.0;
                    vertexColor = vec4(vec3(mcbLight), @EPOINTER@ * float(mcbPointer) + (mcbRev ? 8.0 : 0.0) + mcbAlpha);
                } else if (mcbMap >= 3) {
                    bool mcbFill = mcbMap != 7 && mcb_fill_right(mcb_info(mcbMap)) > 0.5;
                    ivec4 mcbTint = mcbMap != 7 ? mcb_tint_symbols() : ivec4(0);
                    int mcbRed = mcbKind | (mcbFill ? 8 : 0) | (mcbPriority << 4) | (mcbRev ? 1024 : 0);
                    vertexColor = vec4(float(mcbRed), float(mcbBase.x / 128 + 64 * (mcbBase.y / 128)),
                                       float(mcbTint.x * 256 + mcbTint.y), @EVIEW@ + float(mcbTint.z * 256 + mcbTint.w));
                }
            }
            """;

    /**
     * Map colour to symbol, and which maps are ours: both text shaders need it, the vertex one to find
     * every map's kind (and a seated player's own view's size, place and tint) once per vertex, the
     * fragment one to decode the picture.
     */
    private static String decoderCore(Palette palette) {
        SymbolHash hash = palette.symbolHash;
        return MUL_GLSL + """

                // ---- MC GUI hi-colour map decoder ----
                // Colour -> symbol: the palette gives each symbol the colour that hashes to it (SymbolHash), so the hash
                // is the whole lookup: a displacement for one of 64 buckets (128 for an odd palette), bytes four to a
                // uint, read with selects alone. Never a table read by a computed index: AMD's Windows OpenGL driver
                // turns one into seconds a frame and minutes of compiling (Mr Crash's RX 7700 XT, 2026-11-03: 2 FPS
                // with the old table, 119 without).
                uint mcb_displacements(uint i) {
                @DISP@
                }

                int mcb_symbol_of(vec3 rgb) {
                    uvec3 c = uvec3(round(rgb * 255.0));
                    uint k = (c.r << 16u) | (c.g << 8u) | c.b;
                    uint b = mcb_mul(k, @MUL1@u) >> @BSHIFT@u;
                    uint s = ((mcb_mul(k, @MUL2@u) >> 24u) * @SYMBOLS@u >> 8u) + ((mcb_displacements(b >> 2u) >> ((b & 3u) << 3u)) & 255u);
                    return int(s >= @SYMBOLS@u ? s - @SYMBOLS@u : s);
                }

                // Where the map being read starts in Sampler0: (0, 0) unless the texture holds many maps (mcb_texture).
                ivec2 mcbBase = ivec2(0);

                ivec3 mcb_fetch(ivec2 p) { return ivec3(round(texelFetch(Sampler0, mcbBase + p, 0).rgb * 255.0)); }

                int mcb_symbol(ivec2 p) { return mcb_symbol_of(texelFetch(Sampler0, mcbBase + p, 0).rgb); }

                // The symbols of the 2x2 block whose top-left pixel is [p] and has colour [first].
                ivec4 mcb_symbols(ivec2 p, vec3 first) {
                    return ivec4(mcb_symbol_of(first), mcb_symbol(p + ivec2(1, 0)), mcb_symbol(p + ivec2(0, 1)), mcb_symbol(p + ivec2(1, 1)));
                }

                // 1 if Sampler0 is one map's own texture, 2 if it could hold many 128 x 128 maps side by side (ImmediatelyFast's
                // map atlas, 2048 square by default, but a font page of 256 or 1024 is as square), 0 if it holds none.
                int mcb_texture() {
                    ivec2 size = textureSize(Sampler0, 0);
                    if (size == ivec2(128, 128)) return 1;
                    return size.x == size.y && size.x >= 256 && (size.x & 127) == 0 ? 2 : 0;
                }

                bool mcb_in_texture(ivec2 base, ivec2 size) { return all(greaterThanEqual(base, ivec2(0))) && all(lessThan(base, size)); }

                // Which of ours the map at mcbBase is. 0: not ours. 1: a screen. 3: the view of whoever is seated at it, each
                // map carrying its place (bottom markers both the fourth). text.vsh then tells two kinds of 3 apart for
                // text.fsh: 5, an overlay's signature map, and 6, a map of an overlay's panel. 7: a hover layer of a view
                // (Palette.markLayer): top markers swapped too. Only for a texture mcb_texture says can hold maps.
                int mcb_screen_map() {
                    ivec3 m0 = mcb_fetch(ivec2(0, 0)), m1 = mcb_fetch(ivec2(1, 0));
                    bool layer = m0 == @M1@ && m1 == @M0@;
                    if (!layer && (m0 != @M0@ || m1 != @M1@)) return 0;
                    ivec3 a = mcb_fetch(ivec2(0, 1));
                    ivec3 b = mcb_fetch(ivec2(1, 1));
                    if (layer) return a == @M3@ && b == @M3@ ? 7 : 0;
                    if (a == @M2@ && b == @M3@) return 1;
                    if (a == @M3@ && b == @M3@) return 3;
                    return 0;
                }

                // What someone's own view lays over the world where it is transparent (Palette.markTint): red,
                // green, blue and alpha in the 2x2 block after its size.
                vec4 mcb_tint() {
                    return vec4(float(mcb_symbol(ivec2(4, 0))), float(mcb_symbol(ivec2(5, 0))),
                                float(mcb_symbol(ivec2(4, 1))), float(mcb_symbol(ivec2(5, 1)))) / @SYMMAX@;
                }

                // How many 2x2 blocks after the marker hold data, not picture: a view's size, place and tint (2); a
                // hover layer's size, place, speed, flags and two rectangles (7, the top two rows, never shown).
                int mcb_info(int kind) { return kind == 7 ? 7 : kind >= 2 ? 2 : 0; }

                // A 2x2 block's mean luma and its chroma, and 1 (0 for all four when it is transparent).
                vec4 mcb_block(ivec2 b) {
                    ivec2 p = b * 2;
                    vec4 c00 = texelFetch(Sampler0, mcbBase + p, 0);
                    if (c00.a < 0.5) return vec4(0.0);
                    ivec4 s = mcb_symbols(p, c00.rgb);
                    ivec4 digit = s & 7;
                    return vec4(dot(vec4(s >> 3), vec4(0.25)) / @LUMAMAX@, float(digit.x * 8 + digit.y - 32) / 63.0,
                                float(digit.z * 8 + digit.w - 32) / 63.0, 1.0);
                }

                // 1 if a map's data blocks (row 0, up to block [info]) are best shown as the block right of them, 0 if as
                // the blocks below them: right when an edge runs between the top two block rows just past the data, so
                // likely through the data too, below otherwise (nearer, and right for anything that changes along the
                // row). Measured on the market pages' maps, this halves how often the fill differs visibly from the
                // picture it covers, against always right or always below.
                float mcb_fill_right(int info) {
                    vec4 a = mcb_block(ivec2(info + 1, 0)), b = mcb_block(ivec2(info + 1, 1));
                    if (a.w != b.w) return 1.0;
                    vec4 d = abs(a - b);
                    return d.x + 0.5 * (d.y + d.z) > 0.06 ? 1.0 : 0.0;
                }
                """
                .replace("@DISP@", selectTree(hash.displacements))
                .replace("@MUL1@", Integer.toUnsignedString(hash.mul1))
                .replace("@MUL2@", Integer.toUnsignedString(hash.mul2))
                .replace("@BSHIFT@", String.valueOf(32 - hash.bucketBits))
                .replace("@SYMBOLS@", String.valueOf(hash.size))
                .replace("@SYMMAX@", String.format(Locale.ROOT, "%.1f", (float) (Palette.SYMBOLS - 1)))
                .replace("@LUMAMAX@", String.format(Locale.ROOT, "%.1f", (float) (Palette.LUMA_LEVELS - 1)))
                .replace("@M0@", ivec3(palette.markerRgb[0]))
                .replace("@M1@", ivec3(palette.markerRgb[1]))
                .replace("@M2@", ivec3(palette.markerRgb[2]))
                .replace("@M3@", ivec3(palette.markerRgb[3]));
    }

    /**
     * {@code text.fsh}: vanilla's, with ours run first ({@link #marked}). Ours draws a map of ours and a seated player's
     * pointer and returns; anything else is vanilla's (or, merged, the other plugin's) to draw. It reads nothing vanilla's
     * vertex shader does not write: a view and the pointer come in {@code vertexColor} ({@link #ENCODED_VIEW}), and a map
     * on a wall is found here, pixel by pixel, by its marker, so walls draw whoever's text.vsh runs.
     */
    public static String textFsh(Dialect d, Palette palette) {
        boolean oit = d == Dialect.V26_3;
        String emitBody = oit ? """
                #ifdef OIT_ALPHA_ONLY
                    executeAlphaOnlyPhase(depth, c.a);
                #else
                #ifdef OIT_ACCUMULATE
                    c = sampleColorForAccumulation(c);
                #else
                    if (pointer && c.a > 0.99) c.rgb = mcb_pointer_code(c.rgb);
                #endif
                    fragColor = c;
                #endif
                """ : """
                    if (pointer && c.a > 0.99) c.rgb = mcb_pointer_code(c.rgb);
                    fragColor = c;
                """;
        String epilogue = decoderCore(palette) + POINTER_CODE + FRAGMENT_DECODE + """

                // Our fragment's colour out, at [depth]: improved transparency's phases (26.3) as vanilla's own, and the
                // pointer's code for the menu blur to find it by (menuBlurChain), never in the accumulate pass, whose
                // output is a weighted share, not a colour.
                void mcb_emit(vec4 c, float depth, bool pointer) {
                """ + emitBody + "}\n"
                + (d.presplit() ? FRAGMENT_OURS : "#ifndef IS_GUI\n" + FRAGMENT_OURS + "#endif\n") + """

                void main() {
                    // Derivatives before any branch or discard.
                    vec2 mcbFootprint = fwidth(texCoord0 * 128.0);
                """ + (d.presplit() ? """
                    // Before 26.2 this one shader draws GUI and world text, and writes depth for both: a shader that writes
                    // gl_FragDepth anywhere must on every path.
                    gl_FragDepth = gl_FragCoord.z;
                    if (mcb_fragment(mcbFootprint)) return;
                """ : """
                #ifndef IS_GUI
                    // A shader that writes gl_FragDepth anywhere must write it on every path.
                    gl_FragDepth = gl_FragCoord.z;
                #ifndef IS_GRAYSCALE
                    if (mcb_fragment(mcbFootprint)) return;
                #endif
                #endif
                """) + "    " + FRAGMENT_BASE + "();\n}\n";
        epilogue = epilogue.replace("@LUMAMAX@", String.format(Locale.ROOT, "%.1f", (float) (Palette.LUMA_LEVELS - 1)))
                .replace("@SYMMAX@", String.format(Locale.ROOT, "%.1f", (float) (Palette.SYMBOLS - 1)))
                .replace("@SREV@", String.valueOf(SIGNATURE_REVERSED))
                .replace("@SIG0@", (SIGNATURE_0 >> 16) + "," + (SIGNATURE_0 >> 8 & 255) + "," + (SIGNATURE_0 & 255))
                .replace("@SIG3@", String.valueOf(SIGNATURE_3))
                .replace("@PALPHA@", String.format(Locale.ROOT, "%.4f", 255.0 / POINTER_ALPHA))
                .replace("@EPOINTER@", String.format(Locale.ROOT, "%.1f", ENCODED_POINTER))
                .replace("@EVIEW@", String.format(Locale.ROOT, "%.1f", ENCODED_VIEW));
        return emitAsIs(marked(vanillaTextFsh(d), "fragment", FRAGMENT_BASE, "", epilogue));
    }

    /** text.fsh's decoding: a map's pixels averaged under a window pixel, and an overlay's signature. */
    private static final String FRAGMENT_DECODE = """

            // The screen is almost never drawn at one map pixel per window pixel. Instead of the
            // nearest pixel (uneven, shimmering text), average the map pixels under this window
            // pixel's footprint, weighted by overlap. Averaging in YCbCr is exact: the conversion to
            // RGB is linear. Pixels come in 2x2 blocks that share one chroma value; a footprint at most
            // two pixels across touches at most 2x2 of them. The first [info] + 1 blocks of the top row
            // hold data, not picture (the marker, a view's size and place, its tint, a blurred view's
            // panel): they show the block right of them, or each the block below it, as [fillRight] says
            // (mcb_fill_right). A transparent block (map colours 0-3, alpha 0) adds no colour: alpha is how
            // much of the footprint the opaque blocks cover, so a picture's edge against the world is smooth.
            vec4 mcb_sample(vec2 t, vec2 fw, int info, bool fillRight) {
                vec2 halfFw = clamp(fw, vec2(0.02), vec2(2.0)) * 0.5;
                vec2 lo = clamp(t - halfFw, vec2(0.0), vec2(128.0));
                vec2 hi = clamp(t + halfFw, vec2(0.0), vec2(128.0));
                ivec2 b0 = clamp(ivec2(floor(lo * 0.5)), ivec2(0), ivec2(63));
                ivec2 b1 = clamp(ivec2(floor((hi - 0.0001) * 0.5)), ivec2(0), ivec2(63));
                vec3 acc = vec3(0.0);
                float total = 0.0;
                float covered = 0.0;
                for (int i = 0; i < 4; i++) {
                    ivec2 b = b0 + ivec2(i & 1, i >> 1);
                    if (b.x > b1.x || b.y > b1.y) continue;
                    ivec2 base = b * 2;
                    vec2 p0 = vec2(base);
                    vec2 ov0 = max(min(hi, p0 + 1.0) - max(lo, p0), 0.0);
                    vec2 ov1 = max(min(hi, p0 + 2.0) - max(lo, p0 + 1.0), 0.0);
                    vec4 w = vec4(ov0.x * ov0.y, ov1.x * ov0.y, ov0.x * ov1.y, ov1.x * ov1.y);
                    float ws = w.x + w.y + w.z + w.w;
                    total += ws;
                    ivec2 src = b.y > 0 || b.x > info ? base : fillRight ? ivec2(2 * info + 2, 0) : ivec2(base.x, 2);
                    vec4 c00 = texelFetch(Sampler0, mcbBase + src, 0);
                    if (c00.a < 0.5) continue;
                    ivec4 s = mcb_symbols(src, c00.rgb);
                    vec4 luma = vec4(s >> 3) / @LUMAMAX@;
                    ivec4 digit = s & 7;
                    vec2 chroma = vec2(float(digit.x * 8 + digit.y - 32), float(digit.z * 8 + digit.w - 32)) / 63.0;
                    acc += vec3(dot(w, luma), chroma * ws);
                    covered += ws;
                }
                if (covered <= 0.0) return vec4(0.0);
                vec3 ycc = acc / covered;
                return vec4(clamp(vec3(ycc.x + 1.402 * ycc.z, ycc.x - 0.344136 * ycc.y - 0.714136 * ycc.z, ycc.x + 1.772 * ycc.y), 0.0, 1.0),
                            covered / max(total, 1e-6));
            }

            // An overlay's signature, for the post effect that keeps the world round its panel (Shaders.overlayChain),
            // written as the window's four bottom-left pixels: a check, the view's tint, its alpha and the flags (the
            // signature map's own, Palette.markSignature, and whether depth is reversed), and a second check.
            vec3 mcb_signature(int i, vec4 tint, bool reversed) {
                ivec4 t = ivec4(round(clamp(tint, 0.0, 1.0) * 255.0));
                int flags = mcb_symbol(ivec2(6, 0)) | (reversed ? @SREV@ : 0);
                ivec3 c = i == 0 ? ivec3(@SIG0@) : i == 1 ? t.rgb : i == 2 ? ivec3(t.a, flags, 0) : ivec3(0, 0, @SIG3@);
                return vec3(c) / 255.0;
            }
            """;

    /** text.fsh's own drawing: the pointer, a view's maps (from vertexColor), a wall's (found by their marker). */
    private static final String FRAGMENT_OURS = """

            // Draws this fragment if it is ours and says whether it was: a seated player's pointer or a map of their view
            // (both told by text.vsh in vertexColor, Shaders.ENCODED_VIEW), or a map of a wall (found here by its marker,
            // whoever's text.vsh ran). Anything else is left to the text shader we were put after.
            bool mcb_fragment(vec2 mcbFootprint) {
                vec4 code = vertexColor;
                if (code.w >= @EPOINTER@ - 0.5 && code.w < @EVIEW@ - 0.5) {
                    // A seated player's pointer, drawn white, as near as depth goes, in front of the picture and of all else.
                    int kind = int(floor(code.w / @EPOINTER@));
                    float rest = code.w - @EPOINTER@ * float(kind);
                    bool reversed = rest > 4.0;
                    float alpha = clamp(rest - (reversed ? 8.0 : 0.0), 0.0, 1.0);
                    vec4 tex = texture(Sampler0, texCoord0);
                    // The pointer font is stored faint (Shaders.POINTER_ALPHA), so only this shader shows it.
                    tex.a = min(1.0, tex.a * @PALPHA@);
                    vec4 color = tex * vec4(code.rgb, alpha) * ColorModulator;
                    if (color.a < 0.1) discard;
                    float depth = reversed ? 1.0 : 0.0;
                    gl_FragDepth = depth;
                    mcb_emit(color, depth, true);
                    return true;
                }
                int mcbMap;
                bool mcbFill;
                int mcbPriority = 0;
                bool mcbRev = false;
                vec4 mcbViewTint = vec4(0.0);
                vec2 mcbScale = vec2(textureSize(Sampler0, 0)) / 128.0;
                vec2 mcbTex;
                if (code.w >= @EVIEW@ - 0.5) {
                    // A map of someone's own view, placed in their window by text.vsh.
                    int red = int(round(code.r));
                    int green = int(round(code.g));
                    int blue = int(round(code.b));
                    int alpha = int(round(code.w - @EVIEW@));
                    mcbMap = red & 7;
                    mcbFill = (red & 8) != 0;
                    mcbPriority = (red >> 4) & 63;
                    mcbRev = (red & 1024) != 0;
                    mcbBase = ivec2(green % 64, green / 64) * 128;
                    mcbViewTint = vec4(float(blue >> 8), float(blue & 255), float(alpha >> 8), float(alpha & 255)) / @SYMMAX@;
                    mcbTex = texCoord0 * 128.0 * mcbScale - vec2(mcbBase);
                } else {
                    // Maybe a map on a wall. One map's own texture is 128 x 128; a texture of many (ImmediatelyFast's map
                    // atlas, or a font page as square) puts each in a 128 x 128 square, found pixel by pixel.
                    int textureKind = mcb_texture();
                    if (textureKind == 0) return false;
                    vec2 mcbAt = texCoord0 * 128.0 * mcbScale;
                    mcbBase = textureKind == 1 ? ivec2(0) : ivec2(floor(mcbAt / 128.0)) * 128;
                    if (!mcb_in_texture(mcbBase, textureSize(Sampler0, 0))) return false;
                    mcbMap = mcb_screen_map();
                    if (mcbMap == 0) return false;
                    // A view's map that no vertex shader of ours placed (one not seated, or another text.vsh): pieces of
                    // the picture where its frame lies, never wanted.
                    if (mcbMap > 1) discard;
                    mcbTex = mcbAt - vec2(mcbBase);
                    // Its marker block shows a neighbour's (mcb_sample), worked out only where that is near.
                    mcbFill = mcbTex.x < 4.0 && mcbTex.y < 4.0 && mcb_fill_right(0) > 0.5;
                }
                mcbFootprint *= mcbScale;
                vec4 mcbColor;
                float mcbDepth = gl_FragCoord.z;
                if (mcbMap == 5) {
                    if (gl_FragCoord.y >= 1.0 || gl_FragCoord.x >= 4.0) discard;
                    mcbColor = vec4(mcb_signature(int(gl_FragCoord.x), mcbViewTint, mcbRev), 1.0);
                } else {
                    // A hover layer (7) shows only while text.vsh places it, and never its top two rows, its
                    // data: there, and wherever it is transparent, the view under it shows.
                    if (mcbMap == 7 && (mcbPriority == 0 || mcbTex.y < 2.0)) discard;
                    // A screen emits its own light: no lightmap, tint or fog, so colours are exact day and night.
                    vec4 mcbPicture = mcb_sample(mcbTex, mcbFootprint, mcb_info(mcbMap), mcbMap != 7 && mcbFill);
                    // An overlay's panel (6) is there or not, never blended with the world behind it: round it the
                    // post effect shows the world as it was, tinted there. A hover layer likewise over its view.
                    if (mcbMap >= 6) mcbPicture = mcbPicture.a < 0.5 ? vec4(0.0) : vec4(mcbPicture.rgb, 1.0);
                    // Where the picture is transparent the world behind shows, on someone's own view under its
                    // tint: the picture over the tint over the world, as one colour blended over the world.
                    vec4 mcbTint = mcbMap >= 2 && mcbMap < 6 && mcbPicture.a < 1.0 ? mcbViewTint : vec4(0.0);
                    float mcbAlpha = 1.0 - (1.0 - mcbPicture.a) * (1.0 - mcbTint.a);
                    if (mcbAlpha < 0.002) discard;
                    mcbColor = vec4((mcbPicture.rgb * mcbPicture.a + mcbTint.rgb * (mcbTint.a * (1.0 - mcbPicture.a))) / max(mcbAlpha, 0.002), mcbAlpha);
                }
                if (mcbMap >= 2) {
                    // A view (3, 6) is just behind its pointer, in front of all else: its frames are at the
                    // camera, so its own depth means nothing. A signature (5) is nearest. A hover layer (7) is a
                    // step or more in front of its view, the higher its priority the nearer, and still behind the
                    // pointer, whichever is drawn first. No block between the camera and the picture needs clearing.
                    float mcbStep = mcbMap == 7 ? float(mcbPriority) * 1.2e-7 : 0.0;
                    mcbDepth = mcbMap == 5 ? (mcbRev ? 1.0 : 0.0) : (mcbRev ? 0.99999 + mcbStep : 0.00001 - mcbStep);
                    gl_FragDepth = mcbDepth;
                }
                mcb_emit(mcbColor, mcbDepth, false);
                return true;
            }
            """;

    /**
     * A 32 x 32-bit uint multiply (the low 32 bits, as GLSL's own) built from 16-bit halves. Intel's Mesa compiler
     * (iris, and Zink over ANV) gets a full 32 x 32 multiply wrong on GPUs without a native one, such as Meteor Lake's
     * Arc iGPU, which scrambled every hash here: views drawn in the wrong places and in the wrong colours (joleif,
     * Mesa 26.2, 2026-11-07). Each partial product here is 16 x 16 bits, which every GPU gets right, and the answer
     * is the same everywhere, so {@link SymbolHash} still agrees with it bit for bit. Every constant multiply by a
     * full 32-bit number in these shaders goes through it; a small one (a symbol count, 240) does not need to.
     */
    static final String MUL_GLSL = """

            uint mcb_mul(uint a, uint b) {
                uint al = a & 65535u, ah = a >> 16u, bl = b & 65535u, bh = b >> 16u;
                return al * bl + ((al * bh + ah * bl) << 16u);
            }
            """;

    // ---------------------------------------------------------------- the pointer, out of the menu blur

    /**
     * The low two bits of each channel of a pointer pixel at window pixel [p] (26.2's text.fsh writes them, see
     * {@link #menuBlurChain}): a hash of the position, so a flat or smoothly shaded picture almost never carries
     * the code in two neighbouring pixels, as every pointer pixel inside the pointer does. White is at worst
     * (252, 252, 252).
     */
    private static final String CODE_GLSL = """

            ivec3 mcb_code(ivec2 p) {
                uint h = mcb_mul(uint(p.x), 0x9E3779B1u) ^ mcb_mul(uint(p.y), 0x85EBCA77u);
                h ^= h >> 15;
                h = mcb_mul(h, 0x2C1B3C6Du);
                h ^= h >> 12;
                int e = int(h >> 26);
                return ivec3(e & 3, (e >> 2) & 3, (e >> 4) & 3);
            }
            """;

    /** text.fsh's half of the pointer code. */
    private static final String POINTER_CODE = CODE_GLSL + """

            vec3 mcb_pointer_code(vec3 rgb) {
                ivec3 c = ivec3(round(clamp(rgb, 0.0, 1.0) * 255.0));
                return vec3((c & ~3) | mcb_code(ivec2(gl_FragCoord.xy))) / 255.0;
            }
            """;

    /** How far a pointer pixel's fill looks for the picture round the pointer, in window pixels each way. */
    static final int POINTER_REACH = 128;

    /**
     * {@code post_effect/blur.json}, the game's menu blur, the same in 26.2 and 26.3. The game runs it over the frame, world drawn,
     * when a screen asks for a blurred background: the pause menu and the option screens it leads to, with
     * "Menu Background Blur" at 1 or more ({@code Screen.extractBlurredBackground}, {@code GuiRenderer.draw}).
     * Those are the times the system's own cursor is out, so the pointer would be a second one. Three passes
     * first take it out:
     *
     * <ol>
     *   <li>{@code pointer_find}: a pixel is the pointer's if its low bits carry the code for where it is
     *   ({@code mcb_code}, which text.fsh gives every opaque pointer pixel) and at least two of its eight
     *   neighbours' do too. Kept in alpha.</li>
     *   <li>{@code pointer_grow}: every pixel within two of such a pixel is to go, which takes in the pointer's
     *   blended edge and any thin end.</li>
     *   <li>{@code pointer_fill}: each of those is filled from the nearest pixels left, right, above and below
     *   that stay, in proportion to how near each is.</li>
     * </ol>
     *
     * <p>Then the game's own six box-blur passes, unchanged. Anyone without a pointer loses nothing but the odd
     * pixel of very busy picture, refilled from beside it before the blur. The depth buffer would have been the
     * better mark, but the game clears it before the GUI.
     */
    public static String menuBlurChain() {
        StringBuilder passes = new StringBuilder("""
                    {
                      "vertex_shader": "minecraft:core/screenquad",
                      "fragment_shader": "uiengine:post/pointer_find",
                      "inputs": [{"sampler_name": "In", "target": "minecraft:main"}],
                      "output": "ptr_a"
                    },
                    {
                      "vertex_shader": "minecraft:core/screenquad",
                      "fragment_shader": "uiengine:post/pointer_grow",
                      "inputs": [{"sampler_name": "In", "target": "ptr_a"}],
                      "output": "ptr_b"
                    },
                    {
                      "vertex_shader": "minecraft:core/screenquad",
                      "fragment_shader": "uiengine:post/pointer_fill",
                      "inputs": [{"sampler_name": "In", "target": "ptr_b"}],
                      "output": "minecraft:main"
                    }""");
        for (int k = 0; k < 6; k++) {
            passes.append(",\n").append("""
                    {
                      "vertex_shader": "minecraft:core/screenquad",
                      "fragment_shader": "minecraft:post/box_blur",
                      "inputs": [{"sampler_name": "In", "target": "%s", "bilinear": true}],
                      "output": "%s",
                      "uniforms": {"BlurConfig": [{"name": "BlurDir", "type": "vec2", "value": [%s]}, {"name": "Radius", "type": "float", "value": 0.0}]}
                    }""".formatted(k % 2 == 0 ? "minecraft:main" : "swap", k % 2 == 0 ? "swap" : "minecraft:main", k % 2 == 0 ? "1.0, 0.0" : "0.0, 1.0"));
        }
        return "{\n  \"targets\": {\"swap\": {}, \"ptr_a\": {}, \"ptr_b\": {}},\n  \"passes\": [\n" + passes + "\n  ]\n}\n";
    }

    private static final String POINTER_HEAD = """
            #version 330
            #extension GL_ARB_separate_shader_objects : require

            uniform sampler2D InSampler;

            layout(std140) uniform SamplerInfo {
                vec2 OutSize;
            };

            layout(location = 0) in vec2 texCoord;

            layout(location = 0) out vec4 fragColor;
            """;

    /** {@link #menuBlurChain}'s first pass: the frame, alpha 1 where it is the pointer. */
    public static String pointerFindFsh(Dialect d) {
        return emit(POINTER_HEAD + MUL_GLSL + CODE_GLSL + """

                bool coded(ivec2 p) {
                    return (ivec3(round(texelFetch(InSampler, p, 0).rgb * 255.0)) & 3) == mcb_code(p);
                }

                void main() {
                    ivec2 at = ivec2(gl_FragCoord.xy);
                    vec3 here = texelFetch(InSampler, at, 0).rgb;
                    bool pointer = false;
                    if (coded(at)) {
                        ivec2 edge = textureSize(InSampler, 0) - 1;
                        int near = 0;
                        for (int i = 0; i < 9; i++) {
                            ivec2 q = at + ivec2(i % 3 - 1, i / 3 - 1);
                            if (i != 4 && q == clamp(q, ivec2(0), edge) && coded(q)) near++;
                        }
                        pointer = near >= 2;
                    }
                    fragColor = vec4(here, pointer ? 1.0 : 0.0);
                }
                """, d);
    }

    /** The second: alpha 1 within two pixels of the pointer. */
    public static String pointerGrowFsh(Dialect d) {
        return emit(POINTER_HEAD + """

                void main() {
                    ivec2 at = ivec2(gl_FragCoord.xy);
                    ivec2 edge = textureSize(InSampler, 0) - 1;
                    float near = 0.0;
                    for (int i = 0; i < 25; i++) {
                        near = max(near, texelFetch(InSampler, clamp(at + ivec2(i % 5 - 2, i / 5 - 2), ivec2(0), edge), 0).a);
                    }
                    fragColor = vec4(texelFetch(InSampler, at, 0).rgb, near);
                }
                """, d);
    }

    /** The third: the frame with the pointer filled from round it. */
    public static String pointerFillFsh(Dialect d) {
        return emit(POINTER_HEAD + """

                // The first pixel from [at] along [dir] that stays, and how far it is; 0 if there is none in reach.
                vec3 kept(ivec2 at, ivec2 dir, ivec2 edge, out float far) {
                    for (int k = 1; k <= @REACH@; k++) {
                        ivec2 q = at + dir * k;
                        if (q != clamp(q, ivec2(0), edge)) break;
                        vec4 c = texelFetch(InSampler, q, 0);
                        if (c.a < 0.5) {
                            far = float(k);
                            return c.rgb;
                        }
                    }
                    far = 0.0;
                    return vec3(0.0);
                }

                void main() {
                    ivec2 at = ivec2(gl_FragCoord.xy);
                    vec4 here = texelFetch(InSampler, at, 0);
                    if (here.a < 0.5) {
                        fragColor = vec4(here.rgb, 1.0);
                        return;
                    }
                    ivec2 edge = textureSize(InSampler, 0) - 1;
                    vec3 sum = vec3(0.0);
                    float weights = 0.0;
                    for (int i = 0; i < 4; i++) {
                        float far;
                        vec3 c = kept(at, ivec2(i == 0 ? -1 : i == 1 ? 1 : 0, i == 2 ? -1 : i == 3 ? 1 : 0), edge, far);
                        if (far > 0.0) {
                            sum += c / far;
                            weights += 1.0 / far;
                        }
                    }
                    fragColor = vec4(weights > 0.0 ? sum / weights : here.rgb, 1.0);
                }
                """.replace("@REACH@", String.valueOf(POINTER_REACH)), d);
    }

    // ---------------------------------------------------------------- the world round an overlay, kept still

    /** The game's own outline passes ({@code post_effect/entity_outline.json}, the same in 26.2 and 26.3), which {@link #overlayChain} keeps first. */
    private static final String OUTLINE_PASSES = """
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "minecraft:post/entity_sobel",
                  "inputs": [{"sampler_name": "In", "target": "minecraft:entity_outline"}],
                  "output": "swap"
                },
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "minecraft:post/entity_outline_box_blur",
                  "inputs": [{"sampler_name": "In", "target": "swap", "bilinear": true}],
                  "output": "minecraft:entity_outline",
                  "uniforms": {"BlurConfig": [{"name": "BlurDir", "type": "vec2", "value": [1.0, 0.0]}, {"name": "Radius", "type": "float", "value": 2.0}]}
                },
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "minecraft:post/entity_outline_box_blur",
                  "inputs": [{"sampler_name": "In", "target": "minecraft:entity_outline", "bilinear": true}],
                  "output": "swap",
                  "uniforms": {"BlurConfig": [{"name": "BlurDir", "type": "vec2", "value": [0.0, 1.0]}, {"name": "Radius", "type": "float", "value": 2.0}]}
                },
                {
                  "vertex_shader": "minecraft:core/screenquad",
                  "fragment_shader": "minecraft:post/blit",
                  "inputs": [{"sampler_name": "In", "target": "swap"}],
                  "uniforms": {"BlitConfig": [{"name": "ColorModulate", "type": "vec4", "value": [1.0, 1.0, 1.0, 1.0]}]},
                  "output": "minecraft:entity_outline"
                }""";

    /**
     * {@code post_effect/entity_outline.json}. The game runs it whenever a glowing entity was drawn, with
     * {@code minecraft:main} among its targets, whatever the camera ({@code LevelRenderer.render}): in 26.2 right
     * after the world's main pass, sky, blocks and entities, maps included, before clouds and weather; in 26.3 after
     * all of the world, clouds, weather and particles too. An overlay's
     * player has a glowing display that draws nothing as the switch (see {@code Views}), and its signature map
     * says where it is (text.fsh, {@code mcb_signature}). After the game's own outline passes, unchanged:
     *
     * <ol>
     *   <li>While the overlay opens, and whenever the kept picture was dropped (a new window size), the world as
     *   drawn is taken, blurred if the view asks (three rounds of a horizontal and a vertical box blur at the
     *   player's own menu blur setting, as the game's menu blur), into {@code ov_kept}, which the chain keeps
     *   from frame to frame. Otherwise these six passes draw nothing.</li>
     *   <li>Once kept, everything not the view's own (its panel and pointer are at a depth nothing else reaches) is
     *   replaced by the kept picture under the view's tint. So the world round the panel is still and lined up
     *   with what the player saw, however their camera turns as the pointer moves. While it is being taken the
     *   world is left as drawn, so that {@link #transparencyChain} can take it whole.</li>
     *   <li>The outlines are cleared: another entity's would be drawn where it is now.</li>
     * </ol>
     *
     * <p>With no signature every pass of ours draws nothing: anybody not at an overlay sees the game's outlines
     * alone. Each pass's vertex shader works out what its fragments would do and gives the game's window-sized
     * triangle only that much ({@link #overlayTakeVsh}): otherwise every pass costs a fragment for every pixel even
     * when it draws nothing.
     *
     * <p>[particlesAfter] (1.21.11's copy, in its overlay) for a game that draws particles straight after this effect
     * with a pass that never binds {@code Globals} ({@code ParticleFeatureRenderer}), so {@link #particleVsh} reads
     * whatever buffer was last bound where its program looks for it: binding 3, after the pipeline's DynamicTransforms,
     * Projection and Fog ({@code GlProgram.setupUniforms} numbers a pipeline's blocks first, then the built-in ones it
     * finds). The last pass here then declares two blocks of its own after SamplerInfo, so the game binds its
     * {@code Globals} at 3 too, and the particles read this frame's camera.
     */
    public static String overlayChain(boolean particlesAfter) {
        StringBuilder passes = new StringBuilder(OUTLINE_PASSES);
        passes.append(",\n").append(keepPasses("minecraft:main", "ov_a", "ov_b", "ov_kept"));
        passes.append(",\n").append("""
                    {
                      "vertex_shader": "uiengine:post/overlay_signed_in",
                      "fragment_shader": "uiengine:post/overlay_outline",
                      "inputs": [{"sampler_name": "In", "target": "ov_a"}],%s
                      "output": "minecraft:entity_outline"
                    }""".formatted(!particlesAfter ? "" : """

                      "uniforms": {"OverlayHold1": [{"name": "Hold1", "type": "float", "value": 0.0}], "OverlayHold2": [{"name": "Hold2", "type": "float", "value": 0.0}]},"""));
        return "{\n  \"targets\": {\"swap\": {}, \"ov_a\": {}, \"ov_b\": {}, \"ov_kept\": {\"persistent\": true}},\n  \"passes\": [\n"
                + passes + "\n  ]\n}\n";
    }

    /**
     * {@code post_effect/transparency.json}: the game's own improved transparency (1.21.11's Fabulous graphics), then
     * an overlay's world kept once more. Before 26.3 the game draws translucent blocks (water, glass, ice), dropped
     * items, particles, weather and clouds into targets of their own and blends them over the world in this effect,
     * after {@link #overlayChain} has kept the world: so they were missing from the kept picture and drawn live over
     * it, turning with the pointer. Here the composite is taken and kept round the panel by the same passes. This
     * effect cannot see the outline effect's targets, and the signature there is already covered with world, so
     * {@link #overlayComposeFsh} hands it on in the alpha of the window's twelve bottom-left pixels and
     * {@link #overlayRelayFsh} draws it back over the composite's four. Without a signature only the game's own two
     * passes draw. 26.3 has no such effect: its improved transparency blends in the main pass, before the outline one.
     */
    public static String transparencyChain() {
        String composite = """
                    {
                      "vertex_shader": "minecraft:core/screenquad",
                      "fragment_shader": "minecraft:post/transparency",
                      "inputs": [
                        {"sampler_name": "Main", "target": "minecraft:main"},
                        {"sampler_name": "MainDepth", "target": "minecraft:main", "use_depth_buffer": true},
                        {"sampler_name": "Translucent", "target": "minecraft:translucent"},
                        {"sampler_name": "TranslucentDepth", "target": "minecraft:translucent", "use_depth_buffer": true},
                        {"sampler_name": "ItemEntity", "target": "minecraft:item_entity"},
                        {"sampler_name": "ItemEntityDepth", "target": "minecraft:item_entity", "use_depth_buffer": true},
                        {"sampler_name": "Particles", "target": "minecraft:particles"},
                        {"sampler_name": "ParticlesDepth", "target": "minecraft:particles", "use_depth_buffer": true},
                        {"sampler_name": "Clouds", "target": "minecraft:clouds"},
                        {"sampler_name": "CloudsDepth", "target": "minecraft:clouds", "use_depth_buffer": true},
                        {"sampler_name": "Weather", "target": "minecraft:weather"},
                        {"sampler_name": "WeatherDepth", "target": "minecraft:weather", "use_depth_buffer": true}
                      ],
                      "output": "final"
                    },
                    {
                      "vertex_shader": "uiengine:post/overlay_relay",
                      "fragment_shader": "uiengine:post/overlay_relay",
                      "inputs": [{"sampler_name": "Main", "target": "minecraft:main"}],
                      "output": "final"
                    }""";
        String blit = """
                    {
                      "vertex_shader": "minecraft:core/screenquad",
                      "fragment_shader": "minecraft:post/blit",
                      "inputs": [{"sampler_name": "In", "target": "final"}],
                      "uniforms": {"BlitConfig": [{"name": "ColorModulate", "type": "vec4", "value": [1.0, 1.0, 1.0, 1.0]}]},
                      "output": "minecraft:main"
                    }""";
        return "{\n  \"targets\": {\"final\": {}, \"tr_a\": {}, \"tr_b\": {}, \"tr_kept\": {\"persistent\": true}},\n  \"passes\": [\n"
                + composite + ",\n" + keepPasses("final", "tr_a", "tr_b", "tr_kept", blit) + "\n  ]\n}\n";
    }

    /**
     * The passes that keep an overlay's world, from [world] (the frame as drawn, the signature in its four
     * bottom-left pixels): six that take it into the persistent [kept], blurred if asked, a mask of the view's own
     * pixels into [a], then [beforeCompose] (passes that leave [a] and [kept] alone), and the compose into main.
     */
    private static String keepPasses(String world, String a, String b, String kept, String... beforeCompose) {
        StringBuilder passes = new StringBuilder();
        String[] from = {world, a, b, a, b, a};
        String[] to = {a, b, a, b, a, kept};
        for (int k = 0; k < 6; k++) {
            String dir = k % 2 == 0 ? "1.0, 0.0" : "0.0, 1.0";
            String inputs = k == 0
                    ? "{\"sampler_name\": \"In\", \"target\": \"" + world + "\"}, {\"sampler_name\": \"Kept\", \"target\": \"" + kept + "\"}"
                    : "{\"sampler_name\": \"In\", \"target\": \"" + from[k] + "\"}, {\"sampler_name\": \"Main\", \"target\": \"" + world + "\"}";
            String shader = k == 0 ? "overlay_take" : "overlay_blur";
            if (k > 0) passes.append(",\n");
            passes.append("""
                    {
                      "vertex_shader": "uiengine:post/%s",
                      "fragment_shader": "uiengine:post/%s",
                      "inputs": [%s],
                      "output": "%s",
                      "uniforms": {"OverlayConfig": [{"name": "BlurDir", "type": "vec2", "value": [%s]}, {"name": "Last", "type": "float", "value": %s}]}
                    }""".formatted(shader, shader, inputs, to[k], dir, k == 5 ? "1.0" : "0.0"));
        }
        passes.append(",\n").append("""
                    {
                      "vertex_shader": "uiengine:post/overlay_signed_main",
                      "fragment_shader": "uiengine:post/overlay_mask",
                      "inputs": [{"sampler_name": "Main", "target": "%s"}, {"sampler_name": "MainDepth", "target": "minecraft:main", "use_depth_buffer": true}],
                      "output": "%s"
                    }""".formatted(world, a));
        for (String pass : beforeCompose) passes.append(",\n").append(pass);
        passes.append(",\n").append("""
                    {
                      "vertex_shader": "uiengine:post/overlay_signed_in",
                      "fragment_shader": "uiengine:post/overlay_compose",
                      "inputs": [{"sampler_name": "In", "target": "%s"}, {"sampler_name": "Kept", "target": "%s"}],
                      "output": "minecraft:main"
                    }""".formatted(a, kept));
        return passes.toString();
    }

    /** How {@link #overlayChain}'s shaders read an overlay's signature. */
    private static final String OVERLAY_SIGNATURE = """

            ivec3 ov_texel(sampler2D s, int x) { return ivec3(round(texelFetch(s, ivec2(x, 0), 0).rgb * 255.0)); }

            // An overlay's signature in [s]'s four bottom-left texels (text.fsh, mcb_signature): its flags plus 8, or 0
            // without one; [tint], the view's tint.
            int ov_signature(sampler2D s, out vec4 tint) {
                tint = vec4(0.0);
                ivec3 c0 = ov_texel(s, 0), c3 = ov_texel(s, 3);
                if (c0 != ivec3(@SIG0@) || c3 != ivec3(0, 0, @SIG3@)) return 0;
                ivec3 c1 = ov_texel(s, 1), c2 = ov_texel(s, 2);
                tint = vec4(vec3(c1), float(c2.r)) / 255.0;
                return c2.g | 8;
            }
            """;

    /** What every fragment shader of {@link #overlayChain} starts with: reading the signature. */
    private static final String OVERLAY_HEAD = """
            #version 330
            #extension GL_ARB_separate_shader_objects : require

            #include <minecraft:globals.glsl>

            layout(std140) uniform SamplerInfo {
                vec2 OutSize;
            };

            layout(location = 0) in vec2 texCoord;

            layout(location = 0) out vec4 fragColor;
            """ + OVERLAY_SIGNATURE;

    /**
     * What every vertex shader of {@link #overlayChain} starts with: the game's screen triangle ({@code ov_uv}, as
     * {@code core/screenquad} makes it), and the three things to do with it: cover the window, cover only its
     * bottom-left pixel (the "not taking" mark the take passes leave for the next), or draw nothing.
     */
    private static final String OVERLAY_VERTEX_HEAD = """
            #version 330
            #extension GL_ARB_separate_shader_objects : require

            layout(location = 0) out vec2 texCoord;
            """ + OVERLAY_SIGNATURE + """

            vec2 ov_uv() {
                vec2 uv = vec2((@VERTEX@ << 1) & 2, @VERTEX@ & 2);
                texCoord = uv;
                return uv;
            }

            void ov_window(vec2 uv) { gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0); }

            // Legs two pixels long: the bottom-left pixel's centre is well inside, and nothing else's but along an edge.
            void ov_corner(vec2 uv, vec2 size) { gl_Position = vec4(uv * 2.0 / size - 1.0, 0.0, 1.0); }

            // Every corner at one point, off the window: no fragment at all.
            void ov_none() { gl_Position = vec4(-2.0, -2.0, 0.0, 1.0); }
            """;

    /** A box blur of [In] along BlurDir at the menu blur radius, or [In] itself; [signature]: the signature's four texels show the row above. */
    private static final String OVERLAY_BLUR = """

            vec3 ov_world(ivec2 p, bool signature) {
                if (signature && p.y == 0 && p.x < 4) p.y = 1;
                return texelFetch(InSampler, p, 0).rgb;
            }

            vec3 ov_blurred(ivec2 at, bool blur, bool signature) {
                if (!blur) return ov_world(at, signature);
                ivec2 dir = ivec2(BlurDir);
                ivec2 edge = textureSize(InSampler, 0) - 1;
                vec3 sum = vec3(0.0);
                for (int k = -MenuBlurRadius; k <= MenuBlurRadius; k++) sum += ov_world(clamp(at + dir * k, ivec2(0), edge), signature);
                return sum / float(2 * MenuBlurRadius + 1);
            }
            """;

    private static String overlayShader(Dialect d, String body) {
        return emit(placeholders(OVERLAY_HEAD + body), d);
    }

    private static String placeholders(String src) {
        return src.replace("@SIG0@", (SIGNATURE_0 >> 16) + ", " + (SIGNATURE_0 >> 8 & 255) + ", " + (SIGNATURE_0 & 255))
                .replace("@SIG3@", String.valueOf(SIGNATURE_3))
                .replace("@BLUR@", String.valueOf(SIGNATURE_BLUR))
                .replace("@KEPT@", String.valueOf(SIGNATURE_KEPT))
                .replace("@REVERSED@", String.valueOf(SIGNATURE_REVERSED));
    }

    /** A vertex shader of {@link #overlayChain}: 26.3 numbers the vertices as Vulkan does, 26.2 as OpenGL. */
    private static String overlayVertexShader(Dialect d, String body) {
        return emit(placeholders(OVERLAY_VERTEX_HEAD + body).replace("@VERTEX@", d == Dialect.V26_3 ? "gl_VertexIndex" : "gl_VertexID"), d);
    }

    /**
     * {@link #overlayChain}'s first pass: the world as drawn, the first horizontal blur done, while the overlay
     * opens or once the kept picture has been dropped. Its texel (0, 0) tells the passes after it whether a
     * picture is being taken: alpha 1, or 0 for not.
     */
    public static String overlayTakeFsh(Dialect d) {
        return overlayShader(d, """
                uniform sampler2D InSampler;
                uniform sampler2D KeptSampler;

                layout(std140) uniform OverlayConfig {
                    vec2 BlurDir;
                    float Last;
                };
                """ + OVERLAY_BLUR + """

                void main() {
                    ivec2 at = ivec2(gl_FragCoord.xy);
                    vec4 tint;
                    int flags = ov_signature(InSampler, tint);
                    if (flags == 0) discard;
                    // A new window size drops the kept picture: its alpha is 0 until one is kept again.
                    bool take = (flags & @KEPT@) == 0 || texelFetch(KeptSampler, ivec2(0), 0).a < 0.5;
                    if (!take) {
                        if (at != ivec2(0)) discard;
                        fragColor = vec4(0.0);
                        return;
                    }
                    fragColor = vec4(ov_blurred(at, (flags & @BLUR@) != 0, true), 1.0);
                }
                """);
    }

    /** {@link #overlayChain}'s next five passes, the last into the kept picture: nothing unless the first took one. */
    public static String overlayBlurFsh(Dialect d) {
        return overlayShader(d, """
                uniform sampler2D InSampler;
                uniform sampler2D MainSampler;

                layout(std140) uniform OverlayConfig {
                    vec2 BlurDir;
                    float Last;
                };
                """ + OVERLAY_BLUR + """

                void main() {
                    ivec2 at = ivec2(gl_FragCoord.xy);
                    vec4 tint;
                    int flags = ov_signature(MainSampler, tint);
                    if (flags == 0) discard;
                    if (texelFetch(InSampler, ivec2(0), 0).a < 0.5) {
                        // Not taking one: the kept picture stays as it is.
                        if (Last > 0.5 || at != ivec2(0)) discard;
                        fragColor = vec4(0.0);
                        return;
                    }
                    fragColor = vec4(ov_blurred(at, (flags & @BLUR@) != 0, false), 1.0);
                }
                """);
    }

    /** The world as drawn, alpha 1 where it is the view's own (its panel, pointer and signature: nearer than anything else). */
    public static String overlayMaskFsh(Dialect d) {
        return overlayShader(d, """
                uniform sampler2D MainSampler;
                uniform sampler2D MainDepthSampler;

                void main() {
                    ivec2 at = ivec2(gl_FragCoord.xy);
                    vec4 tint;
                    int flags = ov_signature(MainSampler, tint);
                    if (flags == 0) discard;
                    float z = texelFetch(MainDepthSampler, at, 0).r;
                    bool own = (flags & @REVERSED@) != 0 ? z > 0.99995 : z < 0.00005;
                    fragColor = vec4(texelFetch(MainSampler, at, 0).rgb, own ? 1.0 : 0.0);
                }
                """);
    }

    /**
     * Into main: the view's own where it is, the kept world under the tint everywhere else; while the world is still
     * being taken (not yet kept), the world as drawn, untinted, so {@link #transparencyChain} can take it whole. The
     * signature's pixels show the world above them, and the signature goes on in the alpha of the window's twelve
     * bottom-left pixels, a byte each, for {@link #overlayRelayFsh} (alpha in main is never shown).
     */
    public static String overlayComposeFsh(Dialect d) {
        return overlayShader(d, """
                uniform sampler2D InSampler;
                uniform sampler2D KeptSampler;

                void main() {
                    ivec2 at = ivec2(gl_FragCoord.xy);
                    vec4 tint;
                    int flags = ov_signature(InSampler, tint);
                    if (flags == 0) discard;
                    bool signature = at.y == 0 && at.x < 4;
                    float alpha = at.y == 0 && at.x < 12 ? texelFetch(InSampler, ivec2(at.x / 3, 0), 0)[at.x % 3] : 1.0;
                    vec4 here = texelFetch(InSampler, at, 0);
                    if (here.a > 0.5 && !signature) {
                        fragColor = vec4(here.rgb, alpha);
                        return;
                    }
                    vec3 live = texelFetch(InSampler, signature ? ivec2(at.x, 1) : at, 0).rgb;
                    if ((flags & @KEPT@) == 0) {
                        fragColor = vec4(live, alpha);
                        return;
                    }
                    vec4 kept = texelFetch(KeptSampler, at, 0);
                    fragColor = vec4(mix(kept.a > 0.5 ? kept.rgb : live, tint.rgb, tint.a), alpha);
                }
                """);
    }

    /** The signature texel [i] (0 to 3) as {@link #overlayComposeFsh} hands it on, in the alpha of [s]'s bottom-left pixels. */
    private static final String OVERLAY_RELAYED = """

            ivec3 ov_relayed(sampler2D s, int i) {
                return ivec3(round(vec3(texelFetch(s, ivec2(3 * i, 0), 0).a, texelFetch(s, ivec2(3 * i + 1, 0), 0).a,
                        texelFetch(s, ivec2(3 * i + 2, 0), 0).a) * 255.0));
            }

            bool ov_relay_signed(sampler2D s) {
                return ov_relayed(s, 0) == ivec3(@SIG0@) && ov_relayed(s, 3) == ivec3(0, 0, @SIG3@);
            }
            """;

    /** {@link #transparencyChain}'s second pass: the signature handed on in main's alpha, back over the composite's four bottom-left pixels. */
    public static String overlayRelayFsh(Dialect d) {
        return overlayShader(d, """
                uniform sampler2D MainSampler;
                """ + OVERLAY_RELAYED + """

                void main() {
                    ivec2 at = ivec2(gl_FragCoord.xy);
                    if (at.y != 0 || at.x >= 4 || !ov_relay_signed(MainSampler)) discard;
                    fragColor = vec4(vec3(ov_relayed(MainSampler, at.x)) / 255.0, 1.0);
                }
                """);
    }

    /** Its triangle: the four bottom-left pixels with a signature handed on, else nothing. */
    public static String overlayRelayVsh(Dialect d) {
        return overlayVertexShader(d, """

                uniform sampler2D MainSampler;
                """ + OVERLAY_RELAYED + """

                void main() {
                    vec2 uv = ov_uv();
                    // Legs eight pixels along and two up: the four bottom-left pixels' centres are inside.
                    if (ov_relay_signed(MainSampler)) gl_Position = vec4(uv * vec2(8.0, 2.0) / vec2(textureSize(MainSampler, 0)) - 1.0, 0.0, 1.0);
                    else ov_none();
                }
                """);
    }

    /**
     * Clears the outlines while an overlay is open: another glowing entity's would be drawn where it is now, over the
     * still world. On 1.21.11 it also declares the two blocks {@code overlayChain(true)} gives it, and reads them, its
     * SamplerInfo and its Globals, so all four stay in the program in that order and the game binds Globals at 3, where
     * the particles drawn next look for it ({@link #overlayChain}).
     */
    public static String overlayOutlineFsh(Dialect d) {
        boolean hold = d == Dialect.V1_21_11;
        return overlayShader(d, (!hold ? "" : """
                layout(std140) uniform OverlayHold1 {
                    float Hold1;
                };

                layout(std140) uniform OverlayHold2 {
                    float Hold2;
                };

                """) + """
                uniform sampler2D InSampler;

                void main() {
                    vec4 tint;
                    if (ov_signature(InSampler, tint) == 0@HOLD@) discard;
                    fragColor = vec4(0.0);
                }
                """.replace("@HOLD@", !hold ? "" : " || Hold1 + Hold2 + GameTime + OutSize.x < -1.0"));
    }

    /**
     * The first take pass's triangle: the window while a picture is taken (see {@link #overlayTakeFsh}), the
     * bottom-left pixel for the mark that says none is, and nothing without a signature. The game draws every post
     * pass over the whole window, and a fragment that only finds it has nothing to do still costs: these six passes
     * were about 0.2 ms of a still overlay's frame at 1080p on a test PC, and with no signature every pass
     * of the chain ran over the window for anybody with a glowing entity in sight.
     */
    public static String overlayTakeVsh(Dialect d) {
        return overlayVertexShader(d, """

                uniform sampler2D InSampler;
                uniform sampler2D KeptSampler;

                void main() {
                    vec2 uv = ov_uv();
                    vec4 tint;
                    int flags = ov_signature(InSampler, tint);
                    if (flags == 0) ov_none();
                    else if ((flags & @KEPT@) == 0 || texelFetch(KeptSampler, ivec2(0), 0).a < 0.5) ov_window(uv);
                    else ov_corner(uv, vec2(textureSize(InSampler, 0)));
                }
                """);
    }

    /** The next five take passes' (see {@link #overlayBlurFsh}): the window while taking, else the mark, none for the last. */
    public static String overlayBlurVsh(Dialect d) {
        return overlayVertexShader(d, """

                uniform sampler2D InSampler;
                uniform sampler2D MainSampler;

                layout(std140) uniform OverlayConfig {
                    vec2 BlurDir;
                    float Last;
                };

                void main() {
                    vec2 uv = ov_uv();
                    vec4 tint;
                    if (ov_signature(MainSampler, tint) == 0) ov_none();
                    else if (texelFetch(InSampler, ivec2(0), 0).a >= 0.5) ov_window(uv);
                    else if (Last > 0.5) ov_none();
                    else ov_corner(uv, vec2(textureSize(MainSampler, 0)));
                }
                """);
    }

    /** The mask, compose and outline passes': the window with a signature in [sampler] (InSampler or MainSampler), else nothing. */
    public static String overlaySignedVsh(Dialect d, String sampler) {
        return overlayVertexShader(d, """

                uniform sampler2D @S@;

                void main() {
                    vec2 uv = ov_uv();
                    vec4 tint;
                    if (ov_signature(@S@, tint) == 0) ov_none();
                    else ov_window(uv);
                }
                """.replace("@S@", sampler));
    }

    /**
     * {@code rendertype_clouds.vsh} (before 26.3), drawing nothing for an overlay's camera: 1.21.11 to 26.2 draw clouds
     * after the overlay chain, so they would move over the still world as the camera turns with the pointer. 26.3 draws
     * them before it, into the kept picture.
     */
    public static String cloudsVsh() {
        return emit("""
                #version 330

                #moj_import <minecraft:fog.glsl>
                #moj_import <minecraft:dynamictransforms.glsl>
                #moj_import <minecraft:projection.glsl>
                #moj_import <minecraft:globals.glsl>

                const int FLAG_MASK_DIR = 7;
                const int FLAG_INSIDE_FACE = 1 << 4;
                const int FLAG_USE_TOP_COLOR = 1 << 5;
                const int FLAG_EXTRA_Z = 1 << 6;
                const int FLAG_EXTRA_X = 1 << 7;

                layout(std140) uniform CloudInfo {
                    vec4 CloudColor;
                    vec3 CloudOffset;
                    vec3 CellSize;
                };

                uniform isamplerBuffer CloudFaces;

                out float vertexDistance;
                out vec4 vertexColor;

                const vec3[] vertices = vec3[](
                    vec3(1, 0, 0), vec3(1, 0, 1), vec3(0, 0, 1), vec3(0, 0, 0),
                    vec3(0, 1, 0), vec3(0, 1, 1), vec3(1, 1, 1), vec3(1, 1, 0),
                    vec3(0, 0, 0), vec3(0, 1, 0), vec3(1, 1, 0), vec3(1, 0, 0),
                    vec3(1, 0, 1), vec3(1, 1, 1), vec3(0, 1, 1), vec3(0, 0, 1),
                    vec3(0, 0, 1), vec3(0, 1, 1), vec3(0, 1, 0), vec3(0, 0, 0),
                    vec3(1, 0, 0), vec3(1, 1, 0), vec3(1, 1, 1), vec3(1, 0, 1)
                );

                const vec4[] faceColors = vec4[](
                    vec4(0.7, 0.7, 0.7, 1.0), vec4(1.0, 1.0, 1.0, 1.0), vec4(0.8, 0.8, 0.8, 1.0),
                    vec4(0.8, 0.8, 0.8, 1.0), vec4(0.9, 0.9, 0.9, 1.0), vec4(0.9, 0.9, 0.9, 1.0)
                );

                """ + helpers(VERTEX_HELPERS) + """

                void main() {
                    int quadVertex = gl_VertexID % 4;
                    int index = (gl_VertexID / 4) * 3;
                    int cellX = texelFetch(CloudFaces, index).r;
                    int cellZ = texelFetch(CloudFaces, index + 1).r;
                    int dirAndFlags = texelFetch(CloudFaces, index + 2).r;
                    int direction = dirAndFlags & FLAG_MASK_DIR;
                    bool isInsideFace = (dirAndFlags & FLAG_INSIDE_FACE) == FLAG_INSIDE_FACE;
                    bool useTopColor = (dirAndFlags & FLAG_USE_TOP_COLOR) == FLAG_USE_TOP_COLOR;
                    cellX = (cellX << 1) | ((dirAndFlags & FLAG_EXTRA_X) >> 7);
                    cellZ = (cellZ << 1) | ((dirAndFlags & FLAG_EXTRA_Z) >> 6);
                    vec3 faceVertex = vertices[(direction * 4) + (isInsideFace ? 3 - quadVertex : quadVertex)];
                    vec3 pos = (faceVertex * CellSize) + (vec3(cellX, 0, cellZ) * CellSize) + CloudOffset;
                    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
                    // An overlay's camera: outside the clip volume, so nothing is drawn.
                    if (mcb_flag_axis() == 1) gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
                    vertexDistance = fog_spherical_distance(pos);
                    vertexColor = (useTopColor ? faceColors[1] : faceColors[direction]) * CloudColor;
                }
                """, Dialect.V26_2);
    }

    /**
     * {@code rendertype_world_border.vsh} (before 26.3), drawing nothing for an overlay's camera: 1.21.11 to 26.2 draw
     * the world border with the weather, after the overlay chain, so without improved transparency it was drawn live
     * over the still world, turning with the pointer (with it, it is blended in with the weather, which the pack's
     * {@link #transparencyChain} keeps, but the shader cannot tell the two apart). As with clouds, an overlay there
     * shows none. 26.3 draws it in the main pass, into the kept picture.
     */
    public static String worldBorderVsh() {
        return emit("""
                #version 330

                #moj_import <minecraft:dynamictransforms.glsl>
                #moj_import <minecraft:projection.glsl>
                #moj_import <minecraft:globals.glsl>

                in vec3 Position;
                in vec2 UV0;

                out vec2 texCoord0;

                """ + helpers(VERTEX_HELPERS) + """

                void main() {
                    vec3 pos = Position + ModelOffset;
                    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
                    // An overlay's camera: outside the clip volume, so nothing is drawn.
                    if (mcb_flag_axis() == 1) gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
                    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
                }
                """, Dialect.V26_2);
    }

    /** What see-through text's hiding (see {@link #seeThroughVsh}) tests: a seated camera, and not the pointer's colour. */
    private static final String SEE_THROUGH_HIDDEN = """

            // See-through text has no depth test, so for a seated camera it would draw over the view, and an overlay
            // keeps what lies on its panel as its own: there it moved with the pointer. None for a seated camera.
            bool mcb_hidden_see_through() {
                ivec3 rgb = ivec3(round(Color.rgb * 255.0));
                bool pointer = (rgb.r == @PR@ || rgb.r == @PC@) && rgb.g > 0 && rgb.b > 0;
                return mcb_flag_axis() >= 0 && !pointer;
            }
            """.replace("@PR@", String.valueOf(POINTER_RED)).replace("@PC@", String.valueOf(CLIENT_POINTER_RED));

    /**
     * Before 26.2, {@code rendertype_text_see_through.vsh} and {@code rendertype_text_intensity_see_through.vsh}
     * ([textured]) and {@code rendertype_text_background_see_through.vsh}: the game's own, drawing nothing for a seated
     * camera (as text.vsh's {@code IS_SEE_THROUGH} variant from 26.2). Their pipelines have no depth test: a text display
     * with see_through, or a name tag's faint pass, drew over an overlay's panel and moved there with the pointer.
     */
    public static String seeThroughVsh(boolean textured) {
        return emit("""
                #version 330

                #moj_import <minecraft:dynamictransforms.glsl>
                #moj_import <minecraft:projection.glsl>
                #moj_import <minecraft:globals.glsl>

                in vec3 Position;
                in vec4 Color;
                """ + (textured ? "in vec2 UV0;\n" : "") + """

                out vec4 vertexColor;
                """ + (textured ? "out vec2 texCoord0;\n" : "") + helpers(VERTEX_HELPERS) + SEE_THROUGH_HIDDEN + """

                void main() {
                    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
                    if (mcb_hidden_see_through()) gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
                    vertexColor = Color;
                """ + (textured ? "texCoord0 = UV0;\n" : "") + """
                }
                """, Dialect.V26_1);
    }

    /** 26.2's {@code text_background.vsh}, its see-through variant drawing nothing for a seated camera (see {@link #seeThroughVsh}). */
    public static String textBackgroundVsh() {
        return emit("""
                #version 330

                #ifndef IS_SEE_THROUGH
                #moj_import <minecraft:fog.glsl>
                #moj_import <minecraft:sample_lightmap.glsl>
                #endif

                #moj_import <minecraft:dynamictransforms.glsl>
                #moj_import <minecraft:projection.glsl>
                #moj_import <minecraft:globals.glsl>

                in vec3 Position;
                in vec4 Color;
                #ifndef IS_SEE_THROUGH
                in ivec2 UV2;
                uniform sampler2D Sampler2;
                out float sphericalVertexDistance;
                out float cylindricalVertexDistance;
                #endif

                out vec4 vertexColor;
                """ + helpers(VERTEX_HELPERS) + SEE_THROUGH_HIDDEN + """

                void main() {
                    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

                #ifndef IS_SEE_THROUGH
                    sphericalVertexDistance = fog_spherical_distance(Position);
                    cylindricalVertexDistance = fog_cylindrical_distance(Position);
                    vertexColor = Color * sample_lightmap(Sampler2, UV2);
                #else
                    if (mcb_hidden_see_through()) gl_Position = vec4(0.0, 0.0, 2.0, 1.0);
                    vertexColor = Color;
                #endif
                }
                """, Dialect.V26_2);
    }

    /**
     * {@code particle.vsh} (1.21.11 only), drawing nothing for an overlay's camera: 1.21.11 draws particles after the
     * outline effect, so without improved transparency they would move over the still world as the camera turns with
     * the pointer. As with clouds, an overlay there shows none. 26.1 and later draw them in the main pass (or, with
     * improved transparency, into a target {@link #transparencyChain} keeps), and weather, drawn with this shader after
     * the outline effect, is already clear for anybody at a view ({@code Views}).
     */
    public static String particleVsh() {
        return emit("""
                #version 330

                #moj_import <minecraft:fog.glsl>
                #moj_import <minecraft:dynamictransforms.glsl>
                #moj_import <minecraft:projection.glsl>
                #moj_import <minecraft:sample_lightmap.glsl>
                #moj_import <minecraft:globals.glsl>

                in vec3 Position;
                in vec2 UV0;
                in vec4 Color;
                in ivec2 UV2;

                uniform sampler2D Sampler2;

                out float sphericalVertexDistance;
                out float cylindricalVertexDistance;
                out vec2 texCoord0;
                out vec4 vertexColor;

                """ + helpers(VERTEX_HELPERS) + """

                void main() {
                    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
                    // An overlay's camera: outside the clip volume, so nothing is drawn.
                    if (mcb_flag_axis() == 1) gl_Position = vec4(0.0, 0.0, 2.0, 1.0);

                    sphericalVertexDistance = fog_spherical_distance(Position);
                    cylindricalVertexDistance = fog_cylindrical_distance(Position);
                    texCoord0 = UV0;
                    vertexColor = Color * sample_lightmap(Sampler2, UV2);
                }
                """, Dialect.V1_21_11);
    }

    /**
     * The body of a function of {@code uint i} returning {@code words[i]}, by selects alone: whole uvec4s halved on
     * one bit of i at a time, then a component. words.length - 1 selects; a power of two, 4 or more.
     */
    static String selectTree(int[] words) {
        int n = words.length;
        if (n < 4 || Integer.bitCount(n) != 1) throw new IllegalArgumentException("Not a power of two from 4: " + n);
        List<String> groups = new ArrayList<>();
        for (int g = 0; g < n; g += 4) {
            groups.add("uvec4(" + Integer.toUnsignedString(words[g]) + "u, " + Integer.toUnsignedString(words[g + 1]) + "u, "
                    + Integer.toUnsignedString(words[g + 2]) + "u, " + Integer.toUnsignedString(words[g + 3]) + "u)");
        }
        StringBuilder sb = new StringBuilder();
        int bit = n / 2, level = 0;
        while (groups.size() > 1) {
            int half = groups.size() / 2;
            List<String> next = new ArrayList<>();
            for (int j = 0; j < half; j++) {
                String name = "t" + level + "_" + j;
                sb.append("    uvec4 ").append(name).append(" = (i & ").append(bit).append("u) != 0u ? ")
                        .append(groups.get(j + half)).append(" : ").append(groups.get(j)).append(";\n");
                next.add(name);
            }
            groups = next;
            bit >>= 1;
            level++;
        }
        String v = groups.get(0);
        return sb.append("    uvec2 h = (i & 2u) != 0u ? ").append(v).append(".zw : ").append(v).append(".xy;\n")
                .append("    return (i & 1u) != 0u ? h.y : h.x;").toString();
    }

    private static String ivec3(int rgb) {
        return "ivec3(" + (rgb >> 16 & 255) + "," + (rgb >> 8 & 255) + "," + (rgb & 255) + ")";
    }
}
