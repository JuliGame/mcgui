package ai.resourcepack.ui.framework.pack;

import ai.resourcepack.ui.framework.render.Palette;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Packs for the merge tests: ours laid out as ResourcePack lays it out, with only its two vertex shaders. */
final class TestPacks {
    private TestPacks() {}

    static final Palette PALETTE = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));

    /** Our pack's pack.mcmeta and vertex shaders, for every dialect. */
    static Map<String, String> ours() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pack.mcmeta", ResourcePack.mcmeta());
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            for (String base : d.overlay() == null ? List.of("", ResourcePack.overlayOf(d) + "/") : List.of(ResourcePack.overlayOf(d) + "/")) {
                files.put(base + "assets/minecraft/shaders/core/" + d.textShader() + ".vsh", Shaders.textVsh(d, PALETTE));
                files.put(base + "assets/minecraft/shaders/core/position_tex_color.vsh", Shaders.positionTexColorVsh(d));
            }
        }
        return files;
    }

    /** [files] with an overlay [dir] listed last for every format, holding [extra] (paths under the overlay). */
    static Map<String, String> withOverlay(Map<String, String> files, String dir, Map<String, String> extra) {
        Map<String, String> out = new LinkedHashMap<>(files);
        com.google.gson.JsonObject meta = com.google.gson.JsonParser.parseString(out.get("pack.mcmeta")).getAsJsonObject();
        meta.getAsJsonObject("overlays").getAsJsonArray("entries").add(com.google.gson.JsonParser.parseString(
                "{\"directory\":\"" + dir + "\",\"min_format\":75,\"max_format\":97,\"formats\":[75,97]}"));
        out.put("pack.mcmeta", meta.toString());
        for (Map.Entry<String, String> e : extra.entrySet()) out.put(dir + "/" + e.getKey(), e.getValue());
        return out;
    }

    static byte[] zip(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> e : files.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    static Map<String, byte[]> bytes(Map<String, String> files) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        files.forEach((k, v) -> out.put(k, v.getBytes(StandardCharsets.UTF_8)));
        return out;
    }

    /** A plugin's own code merged into one of our shaders: our main renamed, theirs added, a main calling both. */
    static String renamedMerge(String ours, String theirCode, String theirMain) {
        return ours.replace("void main() {", "void mcgui_main() {") + theirCode
                + "\nvoid main() {\n    " + theirMain + "();\n    mcgui_main();\n}\n";
    }

    static final String BALL_ARROW = """
            void pb_main() {
                if (ivec3(round(Color.rgb * 255.0)) == ivec3(254, 1, 2)) gl_Position.y += 0.1;
            }
            """;
}
