package ai.resourcepack.ui.framework.pack;

import ai.resourcepack.common.shader.MergedShaders;
import ai.resourcepack.common.shader.PackSource;
import ai.resourcepack.common.shader.ShaderClashes;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Another plugin's text shader, merged into ours so both draw.
 *
 * <p>A client keeps one copy of each core shader, the top pack's. HUD and text-effect plugins (BetterHud, HUDEngine,
 * ItemsAdder's text effects, Oraxen's animated glyphs) all draw through the text shader, as MC GUI does, so with two
 * of them one silently stops: their HUD, or every page. Our text shaders are vanilla's with our additions marked
 * ({@link Shaders#textVsh}), the form plugin-common's shader merge reads, so the same additions can be made to the
 * other plugin's shader, version by version ({@link MergedShaders}), and put in our pack in overlays listed last: with
 * our pack on top (merged into another plugin's, mergers list ours after theirs; sent by us, ours is sent again over
 * one that lands later, {@link ResourcePack#raise}), a player gets one shader that does both.
 *
 * <p>The other plugin's pack is whatever it left on disk under its data folder (a built folder or a zip), read when
 * MC GUI starts, as plugin-common's {@code ShaderClashes} finds one. So a plugin that writes its pack after MC GUI has
 * started is merged on the next start. Packs that already hold ours (a pack plugin's merged output) are not another
 * plugin's, and one shader found in several places (HUDEngine's own folder and a copy in Nexo's external_packs) counts
 * once. With two different ones nothing is merged: one file cannot hold three plugins' code.
 */
public final class ForeignShaders {
    private ForeignShaders() {}

    /** Another plugin's pack that replaces the text shader: [plugin] is who made it, [where] where it lies. */
    public record Found(String plugin, String where, PackSource pack) {
        public String label() {
            return plugin + " (" + where + ")";
        }

        /** Whether two packs carry the same text shaders (one plugin's pack found in two places). */
        public boolean sameShaders(Found other) {
            return textShaders(pack).equals(textShaders(other.pack));
        }

        /** Where it lies and what its text shaders say: changes when either does. */
        public String key() {
            return label() + "#" + Integer.toHexString(textShaders(pack).hashCode());
        }
    }

    /** What a merge did: the pack's bytes (the same ones if nothing merged), and what to say about it. */
    public record Result(byte[] zip, List<String> merged, List<String> refused) {}

    /** Overlays of merged shaders are named this plus the first format they cover. */
    static final String DIRECTORY = "uiengine_merged_";

    /**
     * Every other installed plugin's pack on disk that replaces the text shader, one per distinct shader. [folders]
     * is each other plugin's data folder by its name.
     */
    public static List<Found> scan(Map<String, Path> folders, Path pluginsFolder) {
        Map<String, Found> byShader = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : new TreeMap<>(folders).entrySet()) {
            if (!Files.isDirectory(e.getValue())) continue;
            ShaderClashes.Found result;
            try {
                result = ShaderClashes.scan(e.getValue(), pluginsFolder);
            } catch (RuntimeException ex) {
                continue;
            }
            if (!result.shaders().contains("rendertype_text")) continue;
            for (Path pack : result.packs()) {
                Optional<PackSource> source = PackSource.read(e.getKey(), pack).map(ForeignShaders::withoutOurs);
                if (source.isEmpty() || !source.get().hasTextShader()) continue;
                String key = textShaders(source.get());
                byShader.putIfAbsent(key, new Found(e.getKey(), ShaderClashes.relative(pluginsFolder, pack), source.get()));
            }
        }
        return List.copyOf(byShader.values());
    }

    /**
     * A pack without any text shader of ours in it: a pack plugin's output with MC GUI merged in holds ours beside
     * theirs (ItemsAdder's text effects in its own overlays), and a shader already merged by one of ours (marked) cannot
     * be merged into again. What is left is the other plugin's own.
     */
    static PackSource withoutOurs(PackSource pack) {
        Map<String, String> kept = new TreeMap<>();
        for (Map.Entry<String, String> f : pack.files().entrySet()) {
            if (isTextShader(f.getKey()) && (f.getValue().contains(Shaders.MERGE_MARKER) || f.getValue().contains("mcb_"))) continue;
            kept.put(f.getKey(), f.getValue());
        }
        return PackSource.of(pack.name(), kept);
    }

    private static boolean isTextShader(String path) {
        return path.matches("(?:[^/]+/)?assets/minecraft/shaders/core/(?:rendertype_text|text)\\.(?:vsh|fsh|json)");
    }

    /** A pack's text shaders, as one string: what makes two copies of one plugin's pack the same. */
    private static String textShaders(PackSource pack) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> f : pack.files().entrySet()) {
            if (isTextShader(f.getKey())) out.append(f.getKey().replaceFirst("^[^/]+/(?=assets/)", "")).append('\n').append(f.getValue()).append('\n');
        }
        return out.toString();
    }

    /**
     * [zip] (our pack) with [theirs]'s text shader merged in, for every version from [minFormat] that both replace it on
     * and where it can be merged; the reasons for the versions where it cannot.
     */
    public static Result merge(byte[] zip, Found theirs, int minFormat) throws IOException {
        Map<String, byte[]> files = unzip(zip);
        MergedShaders.Plan plan = MergedShaders.plan(List.of(PackSource.ofBytes("MC GUI", files)), theirs.pack(), minFormat);
        List<String> merged = new ArrayList<>(), refused = new ArrayList<>();
        List<MergedShaders.Outcome> usable = new ArrayList<>();
        for (MergedShaders.Outcome o : plan.outcomes()) {
            String why = o.merged() ? unusable(o) : o.refused();
            if (why == null) {
                merged.add(o.versions());
                usable.add(o);
            } else {
                refused.add(o.versions() + ": " + why);
            }
        }
        if (usable.isEmpty()) return new Result(zip, merged, refused);
        JsonObject meta = JsonParser.parseString(new String(files.get("pack.mcmeta"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray entries = meta.getAsJsonObject("overlays").getAsJsonArray("entries");
        for (MergedShaders.Outcome o : usable) {
            String directory = DIRECTORY + o.minFormat();
            // Both keys, as every entry of ours has them (ResourcePack.mcmeta), listed last: the last overlay that applies wins.
            JsonObject entry = new JsonObject();
            entry.addProperty("directory", directory);
            entry.addProperty("min_format", o.minFormat());
            entry.addProperty("max_format", o.maxFormat());
            JsonArray formats = new JsonArray();
            formats.add(o.minFormat());
            formats.add(o.maxFormat());
            entry.add("formats", formats);
            entries.add(entry);
            for (Map.Entry<String, String> f : o.files().entrySet()) {
                files.put(directory + "/" + f.getKey(), f.getValue().getBytes(StandardCharsets.UTF_8));
            }
            // Their includes that their own pack carries (BetterHud's 1.21.11 sample_lightmap.glsl, which that version's
            // game lacks): a player with our pack and not theirs would otherwise fail to compile the merged shader.
            for (String include : includes(o)) {
                theirs.pack().resolve(include, o.minFormat())
                        .ifPresent(text -> files.put(directory + "/" + include, text.getBytes(StandardCharsets.UTF_8)));
            }
        }
        files.put("pack.mcmeta", (new GsonBuilder().disableHtmlEscaping().create().toJson(meta) + "\n").getBytes(StandardCharsets.UTF_8));
        return new Result(zip(files), merged, refused);
    }

    private static final java.util.regex.Pattern IMPORT =
            java.util.regex.Pattern.compile("(?m)^\\s*#\\s*(?:moj_import|include)\\s*<(?:([a-z0-9_.-]+):)?([^>]+)>");

    /** The pack paths of every file the merged shaders import. */
    static java.util.Set<String> includes(MergedShaders.Outcome o) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String text : o.files().values()) {
            java.util.regex.Matcher m = IMPORT.matcher(text);
            while (m.find()) out.add("assets/" + (m.group(1) == null ? "minecraft" : m.group(1)) + "/shaders/include/" + m.group(2).trim());
        }
        return out;
    }

    /** A varying or attribute at the top level of a shader, with no layout qualifier. */
    private static final java.util.regex.Pattern UNPLACED =
            java.util.regex.Pattern.compile("(?m)^\\s*(?:(?:flat|noperspective|smooth)\\s+)?(?:in|out)\\s+\\w+\\s+\\w+\\s*;");

    /**
     * Why a merged version cannot be used, or null. 26.3 compiles every shader as Vulkan GLSL, where each varying and
     * attribute needs a {@code layout(location)}: a shader written for 26.2 that a pack still lists for 26.3 (ItemsAdder's
     * do) does not compile there, and with it merged the client would drop the whole pack where ours alone draws.
     */
    static String unusable(MergedShaders.Outcome o) {
        if (o.maxFormat() < Shaders.Dialect.V26_3.minFormat) return null;
        for (Map.Entry<String, String> f : o.files().entrySet()) {
            if (!f.getKey().endsWith(".vsh") && !f.getKey().endsWith(".fsh")) continue;
            if (UNPLACED.matcher(f.getValue()).find()) {
                return "their " + f.getKey().substring(f.getKey().lastIndexOf('/') + 1) + " is written for an older version (a varying with no layout(location)), and would not compile";
            }
        }
        return null;
    }

    static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (!entry.isDirectory()) files.put(entry.getName(), in.readAllBytes());
            }
        }
        return files;
    }

    /** Zipped as ResourcePack zips: in order, every entry at time 0, so the same files are the same bytes. */
    static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                ZipEntry entry = new ZipEntry(f.getKey());
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(f.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** Every other plugin's data folder by its name, of those [installed] (never MC GUI's own, [ours]). */
    public static Map<String, Path> folders(Path pluginsFolder, Path ours, Predicate<String> installed) {
        Map<String, Path> out = new TreeMap<>();
        try (Stream<Path> list = Files.list(pluginsFolder)) {
            for (Path p : (Iterable<Path>) list::iterator) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p) && !p.toAbsolutePath().normalize().equals(ours.toAbsolutePath().normalize()) && installed.test(name)) {
                    out.put(name, p);
                }
            }
        } catch (IOException e) {
            // No folder to read: nothing found.
        }
        return out;
    }

    // ---------------------------------------------------------------- the server's own resource pack

    /** Where the server's own resource pack (server.properties resource-pack) is kept once fetched. */
    public static final String SERVER_PACK = "cache/server-pack.zip";

    /** The largest server pack fetched: a bigger one is not read for a text shader. */
    private static final long SERVER_PACK_MAX = 256L << 20;

    /**
     * The server's own resource pack, as last fetched ({@link #fetchServerPack}), if it replaces the text shader: a
     * pack with text effects of its own (wiggling, coloured or animated text) set in server.properties. The game sends
     * it before any plugin's, so ours is above it and its effects stopped; merged, both draw.
     */
    public static Optional<Found> serverPack(Path dataFolder) {
        Path zip = dataFolder.resolve(SERVER_PACK);
        if (!Files.isRegularFile(zip)) return Optional.empty();
        return PackSource.read("server.properties", zip).map(ForeignShaders::withoutOurs).filter(PackSource::hasTextShader)
                .map(p -> new Found("the server's resource pack", "server.properties resource-pack", p));
    }

    /**
     * Fetches the server's own resource pack from [url] into {@link #SERVER_PACK}, and says whether what is kept there
     * changed. Off the main thread: a pack can take seconds.
     */
    public static boolean fetchServerPack(String url, Path dataFolder) throws IOException, InterruptedException {
        java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
        java.net.http.HttpResponse<java.io.InputStream> response = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(60)).header("User-Agent", "MCGUI").GET().build(), java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (java.io.InputStream in = response.body()) {
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
            bytes = in.readNBytes((int) SERVER_PACK_MAX + 1);
        }
        if (bytes.length > SERVER_PACK_MAX) throw new IOException("bigger than " + (SERVER_PACK_MAX >> 20) + " MB");
        Path zip = dataFolder.resolve(SERVER_PACK);
        if (Files.isRegularFile(zip) && java.util.Arrays.equals(Files.readAllBytes(zip), bytes)) return false;
        Files.createDirectories(zip.getParent());
        Path tmp = zip.resolveSibling("server-pack.zip.part");
        Files.write(tmp, bytes);
        Files.move(tmp, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return true;
    }
}
