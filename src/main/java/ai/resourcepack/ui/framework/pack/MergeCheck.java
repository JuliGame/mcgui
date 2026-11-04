package ai.resourcepack.ui.framework.pack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Mode external with no public-url: reads the pack each known pack plugin built (ItemsAdder, Nexo, Oraxen,
 * CraftEngine, BetterHud, RP Engine) and says in the console whether MC GUI's pack came through the merge, for every client
 * version, the way the game reads it: the pack.mcmeta overlays the game would refuse, MC GUI's files missing, from an
 * older MC GUI, or replaced by another plugin's copy. A merge that goes wrong is otherwise silent on the server, and a
 * player either gets no server pack at all or pages drawn without the shaders.
 * <p>
 * The merged packs are read again whenever one changes on disk (a pack plugin zips after MC GUI starts, and again on
 * its own reload command), and a report is only logged when it is new.
 */
public final class MergeCheck {
    /** Where each pack plugin writes the pack it sends, under plugins/. */
    static final Map<String, String> OUTPUTS = new LinkedHashMap<>();

    static {
        OUTPUTS.put("ItemsAdder", "ItemsAdder/output/generated.zip");
        OUTPUTS.put("Nexo", "Nexo/pack/pack.zip");
        OUTPUTS.put("Oraxen", "Oraxen/pack/pack.zip");
        OUTPUTS.put("CraftEngine", "CraftEngine/generated/resource_pack.zip");
        OUTPUTS.put("BetterHud", "BetterHud/build.zip");
        OUTPUTS.put("RPEngine", "RPEngine/output/main.zip");
    }

    /** Each client version's resource pack format: the overlays it applies, and which of our files it reads. */
    static final String[] VERSIONS = {"1.21.11", "26.1", "26.2", "26.3"};
    static final int[][] FORMATS = {{75, 0}, {84, 0}, {88, 0}, {97, 1}};
    /** The game's last format before min_format/max_format (PackFormat.lastPreMinorVersion for resources). */
    private static final int LAST_OLD_FORMAT = 64;

    private final Path plugins;
    /** {@link #OUTPUTS} of the pack plugins this server has. */
    private final Map<String, String> outputs = new LinkedHashMap<>();
    private final Map<String, byte[]> ours;
    private final JsonArray ourOverlays;
    private final Logger logger;
    private final Map<Path, String> seen = new HashMap<>();
    private final Map<Path, String> said = new HashMap<>();
    /** Packs that could not be read, with the stamp they had: one still being written is read again at the next look. */
    private final Map<Path, String> unreadable = new HashMap<>();

    private static final String CLASH = "another plugin's copy is used instead of MC GUI's";
    private static final String CLASH_ADVICE = "Two plugins that replace the same core shader cannot both work in one pack: the "
            + "game uses one copy, and what the other plugin draws with it stops working. Turn off the other plugin's feature "
            + "that replaces it (the overlay named is the plugin's), or that plugin's feature and MC GUI's screens cannot share a server. Or build that shader on MC GUI's: MC GUI's file "
            + "with its main renamed, the plugin's code added, and a main that calls both counts as MC GUI's.";
    private static final String IA_TEXT_SHADER = " In ItemsAdder these are text_effects, hide_scoreboard_numbers and "
            + "hide_scoreboard_numbers_old_clients in its config.yml (all three off); run /iazip after.";
    /** Dialog Engine's drawn-dialog pack ships its own position_tex_color (and, per client version, text shaders) in overlays named like this. */
    private static final String DIALOG_OVERLAY = "rpai_dialog_";
    private static final String DIALOG = "Dialog Engine's drawn-dialog pack is merged in (its overlays " + DIALOG_OVERLAY + "*)";
    private static final String DIALOG_ADVICE = "Dialog Engine sends its own pack, so take its folder (DialogEngine/drawn-pack) out of "
            + "the merge list of that plugin and rebuild its pack: two packs merged into one cannot both be on top, and Dialog Engine's "
            + "copy of position_tex_color replaces MC GUI's.";

    /** [installed] says whether this server has a pack plugin, by its name: only theirs are read. */
    public MergeCheck(Path plugins, byte[] ourZip, Logger logger, java.util.function.Predicate<String> installed) throws IOException {
        this.plugins = plugins;
        for (Map.Entry<String, String> e : OUTPUTS.entrySet()) if (installed.test(e.getKey())) outputs.put(e.getKey(), e.getValue());
        this.ours = unzip(new ZipInputStream(new ByteArrayInputStream(ourZip)));
        this.ourOverlays = overlays(meta(ours));
        this.logger = logger;
    }

    /** Looks at every merged pack that changed since the last look, and logs what is new. Off the server thread. */
    public synchronized void run() {
        for (Map.Entry<String, String> e : outputs.entrySet()) {
            Path zip = plugins.resolve(e.getValue());
            String stamp;
            try {
                stamp = Files.isRegularFile(zip) ? Files.getLastModifiedTime(zip).toMillis() + ":" + Files.size(zip) : null;
            } catch (IOException ex) {
                stamp = null;
            }
            if (stamp == null || stamp.equals(seen.get(zip))) continue;
            String where = "plugins/" + e.getValue();
            List<String> problems;
            TreeSet<String> merged = new TreeSet<>();
            try (ZipFile z = new ZipFile(zip.toFile())) {
                problems = check(z, merged);
                unreadable.remove(zip);
            } catch (IOException | RuntimeException ex) {
                // Read while its plugin was still writing it ("zip END header not found"), most likely: said only if
                // the same file fails again at the next look.
                if (!stamp.equals(unreadable.put(zip, stamp))) continue;
                problems = List.of("it could not be read (" + ex.getMessage() + ")");
            }
            seen.put(zip, stamp);
            String report = String.join("\n", problems) + "\n" + merged;
            if (report.equals(said.get(zip))) continue;
            said.put(zip, report);
            if (problems.isEmpty()) {
                logger.info(e.getKey() + "'s pack (" + where + ") has MC GUI's pack merged in right for every version"
                        + (merged.isEmpty() ? "." : ", with its own code merged into MC GUI's " + String.join(", ", merged) + "."));
            } else {
                boolean clash = false, dialog = false;
                for (String p : problems) {
                    clash |= p.contains(CLASH);
                    dialog |= p.startsWith(DIALOG);
                }
                logger.warning(e.getKey() + "'s pack (" + where + ") does not have MC GUI's pack merged in right, so screens "
                        + "will not work for players it is sent to:\n  - " + String.join("\n  - ", problems) + "\n  "
                        + (dialog ? DIALOG_ADVICE + " " : "")
                        + (clash ? CLASH_ADVICE + (e.getKey().equals("ItemsAdder") ? IA_TEXT_SHADER : "") : advice(e.getKey())));
            }
        }
    }

    /**
     * What is wrong with a merged pack, as sentences; none when every version gets all our files. A shader of ours that
     * another plugin's code was merged into counts as ours when it still carries all of ours ({@link ShaderCode}): its
     * file name is added to [merged].
     */
    List<String> check(ZipFile zip, java.util.Set<String> merged) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
            if (entry.isDirectory()) continue;
            String name = entry.getName();
            // Only what a comparison needs: the merged pack can be hundreds of MB of textures.
            if (!name.equals("pack.mcmeta") && !name.contains("/shaders/") && !name.contains("/post_effect/")) continue;
            try (InputStream in = zip.getInputStream(entry)) {
                files.put(name, in.readAllBytes());
            }
        }
        List<String> problems = new ArrayList<>();
        JsonObject meta = meta(files);
        if (meta == null) {
            problems.add("it has no pack.mcmeta the game can read");
            return problems;
        }
        JsonArray overlays = overlays(meta);
        String invalid = invalidOverlays(overlays);
        if (invalid != null) {
            problems.add("its pack.mcmeta has an overlay entry the game refuses (" + invalid + "), and the game then refuses the whole pack");
        }
        for (JsonElement m : overlays) {
            if (m.getAsJsonObject().get("directory").getAsString().startsWith(DIALOG_OVERLAY)) {
                problems.add(DIALOG);
                break;
            }
        }
        TreeSet<String> missingEntries = new TreeSet<>();
        for (JsonElement o : ourOverlays) {
            String dir = o.getAsJsonObject().get("directory").getAsString();
            if (dir.equals("uiengine_none")) continue;
            boolean listed = false;
            for (JsonElement m : overlays) listed |= dir.equals(m.getAsJsonObject().get("directory").getAsString());
            if (!listed) missingEntries.add(dir);
        }
        if (!missingEntries.isEmpty()) {
            boolean some = false, any = false;
            for (JsonElement m : overlays) some |= m.getAsJsonObject().get("directory").getAsString().startsWith("uiengine_");
            for (String n : files.keySet()) any |= n.contains("assets/uiengine/");
            problems.add(some
                    ? "its pack.mcmeta lacks MC GUI's overlays " + missingEntries + ": it was built from an older MC GUI's pack"
                    : any
                    ? "its pack.mcmeta lists none of MC GUI's overlays (the merge dropped MC GUI's pack.mcmeta), so every version "
                            + "before 26.3 gets 26.3's shaders, fails to load them and drops the whole pack"
                    : "MC GUI's pack is not in it at all");
            return problems;
        }
        for (int v = 0; v < VERSIONS.length; v++) {
            List<String> missing = new ArrayList<>(), older = new ArrayList<>(), replaced = new ArrayList<>();
            for (String path : logical(ours.keySet(), ourOverlays)) {
                byte[] want = resolve(ours, ourOverlays, FORMATS[v], path).bytes;
                if (want == null) continue;
                Resolved got = resolve(files, overlays, FORMATS[v], path);
                if (got.bytes == null) missing.add(path);
                else if (!Arrays.equals(got.bytes, want)) {
                    String theirs = new String(got.bytes, StandardCharsets.UTF_8);
                    ShaderCode.Missing lacks = null;
                    if (isShader(path)) {
                        int[] format = FORMATS[v];
                        lacks = ShaderCode.carries(new String(want, StandardCharsets.UTF_8), theirs,
                                id -> text(resolve(files, overlays, format, ShaderCode.includePath(id)).bytes));
                        if (lacks == null) {
                            merged.add(leaf(path));
                            continue;
                        }
                    }
                    // Ours, as an older MC GUI wrote it (or another plugin's code merged into an older MC GUI's), or
                    // another plugin's in its place.
                    if (theirs.contains("mcb_") || theirs.contains("mcgui_") || theirs.contains("uiengine:")) {
                        older.add(leaf(path) + (lacks != null ? " (it lacks " + lacks.what() + ")" : ""));
                    } else {
                        replaced.add(leaf(path) + " (from " + got.where + ")");
                    }
                }
            }
            String ver = VERSIONS[v];
            if (!missing.isEmpty()) problems.add(ver + ": " + missing.size() + " of MC GUI's files are missing, e.g. " + leaf(missing.get(0)));
            if (!older.isEmpty()) problems.add(ver + ": " + older.size() + " of MC GUI's files are an older MC GUI's, e.g. " + older.get(0)
                    + ": the pack was built, or MC GUI's code merged into another plugin's shader, before MC GUI was updated");
            if (!replaced.isEmpty()) problems.add(ver + ": " + CLASH + " for " + String.join(", ", replaced));
        }
        return problems;
    }

    private static String advice(String plugin) {
        return switch (plugin) {
            case "ItemsAdder" -> "Add MCGUI/pack under resource-pack.zip.merge_other_plugins_resourcepacks_folders in ItemsAdder's config.yml "
                    + "(leave DialogEngine/drawn-pack out of it: Dialog Engine sends its own pack), turn off text_effects, "
                    + "hide_scoreboard_numbers and hide_scoreboard_numbers_old_clients, then run /iazip.";
            case "Nexo" -> "Copy plugins/MCGUI/pack.zip into plugins/Nexo/pack/external_packs/ and run /nexo reload pack.";
            case "Oraxen" -> "Copy plugins/MCGUI/pack.zip into plugins/Oraxen/pack/uploads/ and run /oraxen reload pack.";
            case "CraftEngine" -> "Add MCGUI/pack under resource-pack.merge-external-folders in CraftEngine's config.yml and run /ce reload pack.";
            case "BetterHud" -> "Add MCGUI/pack under merge-other-folders in BetterHud's config.yml and run /hud reload.";
            case "RPEngine" -> "Copy the folder plugins/MCGUI/pack to plugins/RPEngine/content/mcgui and run /rp reload (again after every MC GUI update).";
            default -> "Merge plugins/MCGUI/pack into it again.";
        };
    }

    static boolean isShader(String path) {
        return path.endsWith(".vsh") || path.endsWith(".fsh") || path.endsWith(".glsl");
    }

    static String text(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    private static String leaf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    record Resolved(byte[] bytes, String where) {
    }

    /** The copy of [path] a client of [format] reads: the last applicable overlay that has it, else the root's. */
    static Resolved resolve(Map<String, byte[]> files, JsonArray overlays, int[] format, String path) {
        for (int i = overlays.size() - 1; i >= 0; i--) {
            JsonObject o = overlays.get(i).getAsJsonObject();
            if (!applies(o, format)) continue;
            String dir = o.get("directory").getAsString();
            byte[] b = files.get(dir + "/" + path);
            if (b != null) return new Resolved(b, dir);
        }
        return new Resolved(files.get(path), "the pack's root");
    }

    /** Every path under assets/ a client can be given from [names], with overlay directories folded away. */
    static List<String> logical(Iterable<String> names, JsonArray overlays) {
        TreeSet<String> dirs = new TreeSet<>();
        for (JsonElement o : overlays) dirs.add(o.getAsJsonObject().get("directory").getAsString());
        TreeSet<String> out = new TreeSet<>();
        for (String n : names) {
            if (!n.contains("/shaders/") && !n.contains("/post_effect/")) continue;
            int slash = n.indexOf('/');
            if (slash > 0 && dirs.contains(n.substring(0, slash))) out.add(n.substring(slash + 1));
            else if (n.startsWith("assets/")) out.add(n);
        }
        return new ArrayList<>(out);
    }

    /** OverlayMetadataSection.OverlayEntry.isApplicable: min_format/max_format (a lone major: .0 and .*), else formats. */
    static boolean applies(JsonObject o, int[] v) {
        int[] lo, hi;
        if (o.has("min_format") && o.has("max_format")) {
            lo = format(o.get("min_format"), 0);
            hi = format(o.get("max_format"), Integer.MAX_VALUE);
        } else if (o.has("formats")) {
            int[] r = range(o.get("formats"));
            if (r == null) return false;
            lo = new int[]{r[0], 0};
            hi = new int[]{r[1], Integer.MAX_VALUE};
        } else {
            return false;
        }
        return compare(lo, v) <= 0 && compare(v, hi) <= 0;
    }

    /**
     * Why the game would refuse this overlays list (PackFormat.validateHolderList and IntermediaryFormat.validate),
     * or null. Only the rules a merge breaks: "formats" missing once any entry reaches below 65, or not matching.
     */
    static String invalidOverlays(JsonArray overlays) {
        int lowest = Integer.MAX_VALUE;
        for (JsonElement e : overlays) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("min_format")) lowest = Math.min(lowest, format(o.get("min_format"), 0)[0]);
            int[] r = o.has("formats") ? range(o.get("formats")) : null;
            if (r != null) lowest = Math.min(lowest, r[0]);
        }
        boolean needOld = lowest <= LAST_OLD_FORMAT;
        for (JsonElement e : overlays) {
            JsonObject o = e.getAsJsonObject();
            String dir = o.has("directory") ? o.get("directory").getAsString() : "?";
            boolean min = o.has("min_format"), max = o.has("max_format");
            int[] old = o.has("formats") ? range(o.get("formats")) : null;
            // The game only warns about an entry with no range at all, and skips it.
            if (!min && !max && old == null) continue;
            if (min != max) return "\"" + dir + "\" has only one of min_format and max_format";
            if (needOld && old == null) return "\"" + dir + "\" has no \"formats\", which every entry needs once one reaches below format 65";
            if (!min) {
                if (old != null && old[1] > LAST_OLD_FORMAT) return "\"" + dir + "\" gives a format above 64 with \"formats\" alone, without min_format and max_format";
                continue;
            }
            int lo = format(o.get("min_format"), 0)[0], hi = format(o.get("max_format"), Integer.MAX_VALUE)[0];
            if (lo > LAST_OLD_FORMAT && !needOld) {
                if (old != null) return "\"" + dir + "\" has \"formats\", which the game refuses when no entry reaches below format 65";
            } else if (old != null && (old[0] != lo || old[1] != hi && old[1] != LAST_OLD_FORMAT)) {
                return "\"" + dir + "\" gives \"formats\" " + old[0] + "-" + old[1] + " and min_format/max_format " + lo + "-" + hi;
            }
        }
        return null;
    }

    private static int[] format(JsonElement e, int defaultMinor) {
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            return new int[]{a.get(0).getAsInt(), a.size() > 1 ? a.get(1).getAsInt() : defaultMinor};
        }
        return new int[]{e.getAsInt(), defaultMinor};
    }

    /** "formats": a number, [min, max] or {min_inclusive, max_inclusive}. */
    private static int[] range(JsonElement e) {
        if (e.isJsonPrimitive()) return new int[]{e.getAsInt(), e.getAsInt()};
        if (e.isJsonArray() && !e.getAsJsonArray().isEmpty()) {
            JsonArray a = e.getAsJsonArray();
            return new int[]{a.get(0).getAsInt(), a.get(a.size() - 1).getAsInt()};
        }
        if (e.isJsonObject() && e.getAsJsonObject().has("min_inclusive") && e.getAsJsonObject().has("max_inclusive")) {
            return new int[]{e.getAsJsonObject().get("min_inclusive").getAsInt(), e.getAsJsonObject().get("max_inclusive").getAsInt()};
        }
        return null;
    }

    private static int compare(int[] a, int[] b) {
        return a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]);
    }

    static JsonObject meta(Map<String, byte[]> files) {
        byte[] b = files.get("pack.mcmeta");
        if (b == null) return null;
        try {
            String text = new String(b, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);
            JsonElement e = JsonParser.parseString(text);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static JsonArray overlays(JsonObject meta) {
        JsonArray out = new JsonArray();
        if (meta == null || !meta.has("overlays") || !meta.get("overlays").isJsonObject()) return out;
        JsonElement entries = meta.getAsJsonObject("overlays").get("entries");
        if (entries == null || !entries.isJsonArray()) return out;
        for (JsonElement e : entries.getAsJsonArray()) {
            if (e.isJsonObject() && e.getAsJsonObject().has("directory")) out.add(e);
        }
        return out;
    }

    static Map<String, byte[]> unzip(ZipInputStream in) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (in) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (!entry.isDirectory()) out.put(entry.getName(), in.readAllBytes());
            }
        }
        return out;
    }
}
