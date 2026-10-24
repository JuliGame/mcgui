package ai.resourcepack.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code /ui import <code>}: the pages somebody shared from <a href="https://mcgui.dev">mcgui.dev</a>,
 * written into the pages folder. The code names a frozen copy there; this fetches its files (pages,
 * stylesheets, pictures) as JSON from {@code import-url} + code, checks every name itself (a server
 * trusts no website to keep names inside its folder), and writes them, replacing files of the same
 * name. Live reload then shows them, and the sender is offered the page to open.
 *
 * <p>A share says which builder project it came from, and {@code imports.json} remembers what each
 * project's last import wrote. Importing a project again removes the files its last import wrote and
 * this one no longer has (a page renamed or deleted on mcgui.dev), but only those still exactly as
 * imported: a file somebody has edited on the server since stays, and so does one another project's
 * import now owns.
 */
final class PageImport {
    /** What a shared code looks like: eight letters and digits, none that read as each other. */
    static final Pattern CODE = Pattern.compile("[a-hjkmnp-z2-9]{8}");
    /** A file name the builder may share: lower case, no folders, a page, a stylesheet or a picture. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}\\.(?:html|css|png|jpe?g|gif|svg)");
    /** A builder project's id, as mcgui.dev makes them. */
    private static final Pattern PROJECT = Pattern.compile("[a-z0-9]{1,24}");
    private static final Object LEDGER_LOCK = new Object();
    private static final Pattern PICTURE = Pattern.compile("data:image/(?:png|jpeg|gif|svg\\+xml);base64,([A-Za-z0-9+/=]+)");
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_FILES = 40;

    private final UIEnginePlugin plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    PageImport(UIEnginePlugin plugin) {
        this.plugin = plugin;
    }

    /** Fetches and writes [code]'s files, telling [sender] how it went. Returns at once; the work is off the server thread. */
    void run(CommandSender sender, String code) {
        String base = plugin.importUrl();
        if (base.isBlank()) {
            say(sender, "Importing is off on this server (import-url in config.yml).", NamedTextColor.RED);
            return;
        }
        String key = code.toLowerCase(java.util.Locale.ROOT);
        if (!CODE.matcher(key).matches()) {
            say(sender, "That isn't a mcgui.dev code: it's the eight letters after /ui import on the Send to server screen.", NamedTextColor.RED);
            return;
        }
        say(sender, "Fetching " + key + "...", NamedTextColor.GRAY);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + key))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "UIEngine/" + plugin.getPluginMeta().getVersion());
        if (plugin.telemetry()) about(builder, sender);
        HttpRequest request = builder.GET().build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).whenComplete((res, error) -> {
            List<String> written = new ArrayList<>();
            List<String> newCursors = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            List<String> kept = new ArrayList<>();
            String problem = null;
            String entry = null;
            try {
                if (error != null) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    throw new IOException("could not reach " + URI.create(base).getHost() + " (" + cause + ")");
                }
                if (res.statusCode() == 404) throw new IOException("no page has that code. Check it, or share it again.");
                if (res.statusCode() != 200) throw new IOException("the site answered " + res.statusCode());
                if (res.body().length > MAX_BYTES) throw new IOException("it is bigger than 4 MB");
                JsonObject body = JsonParser.parseString(new String(res.body(), StandardCharsets.UTF_8)).getAsJsonObject();
                Map<String, byte[]> files = files(body.getAsJsonArray("files"));
                entry = body.has("entry") ? body.get("entry").getAsString() : null;
                Path root = plugin.pages().root();
                Files.createDirectories(root);
                for (Map.Entry<String, byte[]> f : files.entrySet()) {
                    Path target = root.resolve(f.getKey()).normalize();
                    if (!target.getParent().equals(root.normalize())) throw new IOException("a file name tried to leave the pages folder");
                    Files.write(target, f.getValue());
                    written.add(f.getKey());
                }
                String project = body.has("project") && body.get("project").isJsonPrimitive() ? body.get("project").getAsString() : null;
                if (project != null && PROJECT.matcher(project).matches()) tidy(plugin.getDataFolder().toPath().resolve("imports.json"), root, project, files, removed, kept);
                newCursors.addAll(cursors(files));
            } catch (Exception e) {
                problem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            String failed = problem;
            String open = entry != null && NAME.matcher(entry).matches() && entry.endsWith(".html") ? entry.substring(0, entry.length() - 5) : null;
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (failed != null) {
                    plugin.getLogger().warning(sender.getName() + " could not import " + key + ": " + failed);
                    say(sender, "Could not import " + key + ": " + failed, NamedTextColor.RED);
                    return;
                }
                plugin.getLogger().info(sender.getName() + " imported " + key + " from mcgui.dev: " + String.join(", ", written));
                say(sender, "Imported " + written.size() + " file" + (written.size() == 1 ? "" : "s") + " from " + key + ".", NamedTextColor.GREEN);
                if (!removed.isEmpty()) {
                    plugin.getLogger().info("Removed what the last import of that project left behind: " + String.join(", ", removed));
                    say(sender, "Removed " + String.join(", ", removed) + ": no longer in the project.", NamedTextColor.GRAY);
                }
                if (!kept.isEmpty()) {
                    say(sender, "Kept " + String.join(", ", kept) + ": no longer in the project, but changed on the server since the last import. Delete "
                            + (kept.size() == 1 ? "it" : "them") + " by hand if you don't need " + (kept.size() == 1 ? "it." : "them."), NamedTextColor.YELLOW);
                }
                // Every page, as something to click: the one being worked on first.
                List<String> pages = new ArrayList<>();
                if (open != null) pages.add(open);
                for (String f : written) {
                    String page = f.endsWith(".html") ? f.substring(0, f.length() - 5) : null;
                    if (page != null && !pages.contains(page)) pages.add(page);
                }
                if (!pages.isEmpty()) {
                    Component line = Component.text("Open: ", NamedTextColor.GRAY);
                    for (int i = 0; i < pages.size(); i++) {
                        String page = pages.get(i);
                        if (i > 0) line = line.append(Component.text(" · ", NamedTextColor.DARK_GRAY));
                        line = line.append(Component.text(page, i == 0 ? NamedTextColor.AQUA : NamedTextColor.DARK_AQUA)
                                .clickEvent(ClickEvent.runCommand("/ui open " + page))
                                .hoverEvent(HoverEvent.showText(Component.text("/ui open " + page))));
                    }
                    sender.sendMessage(line);
                }
                if (!newCursors.isEmpty()) {
                    say(sender, "New cursor" + (newCursors.size() == 1 ? " " : "s ") + String.join(", ", newCursors)
                            + ": restart the server to put " + (newCursors.size() == 1 ? "it" : "them") + " in the resource pack. Until then the page uses its fallback.", NamedTextColor.YELLOW);
                }
            });
        });
    }

    /**
     * Says which server is importing, so mcgui.dev can list where its pages are used: the port it listens
     * on, its {@code server-ip} if set, its MOTD, the players online and who ran the command. Only while
     * {@code telemetry} is on; the request's own address is the rest.
     */
    private static void about(HttpRequest.Builder request, CommandSender sender) {
        request.header("X-Server-Port", Integer.toString(Bukkit.getPort()));
        String ip = Bukkit.getIp();
        if (ip != null && !ip.isBlank()) request.header("X-Server-Ip", ip.trim());
        String motd = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(Bukkit.motd()).replaceAll("\\s+", " ").trim();
        if (!motd.isEmpty()) request.header("X-Server-Motd", ascii(motd, 200));
        request.header("X-Server-Players", Integer.toString(Bukkit.getOnlinePlayers().size()));
        request.header("X-Imported-By", sender instanceof org.bukkit.entity.Player ? sender.getName() : "console");
    }

    /** A header value: printable ASCII only (Java's client refuses anything else), at most [max] characters. */
    private static String ascii(String value, int max) {
        String clean = value.replaceAll("[^\\x20-\\x7e]", "");
        return clean.length() > max ? clean.substring(0, max) : clean;
    }

    /**
     * Removes the files [project]'s last import wrote that [files] no longer has, if they are still as
     * imported and no other project's import lists them, then remembers [files] as its last import.
     */
    static void tidy(Path ledger, Path root, String project, Map<String, byte[]> files, List<String> removed, List<String> kept) throws IOException {
        synchronized (LEDGER_LOCK) {
            JsonObject all = new JsonObject();
            if (Files.isRegularFile(ledger)) {
                try {
                    JsonElement read = JsonParser.parseString(Files.readString(ledger));
                    if (read.isJsonObject()) all = read.getAsJsonObject();
                } catch (RuntimeException e) {
                    // A broken record forgets what was imported: nothing is removed this time.
                }
            }
            JsonObject before = all.has(project) && all.get(project).isJsonObject() ? all.getAsJsonObject(project) : new JsonObject();
            for (String name : before.keySet()) {
                if (files.containsKey(name) || !NAME.matcher(name).matches() || ownedElsewhere(all, project, name)) continue;
                Path target = root.resolve(name).normalize();
                if (!root.normalize().equals(target.getParent()) || !Files.isRegularFile(target)) continue;
                JsonElement hash = before.get(name);
                if (hash.isJsonPrimitive() && hash.getAsString().equals(sha256(Files.readAllBytes(target)))) {
                    Files.delete(target);
                    removed.add(name);
                } else {
                    kept.add(name);
                }
            }
            JsonObject now = new JsonObject();
            for (Map.Entry<String, byte[]> f : files.entrySet()) now.addProperty(f.getKey(), sha256(f.getValue()));
            all.add(project, now);
            Files.createDirectories(ledger.getParent());
            Files.writeString(ledger, all.toString());
        }
    }

    private static boolean ownedElsewhere(JsonObject all, String project, String name) {
        for (Map.Entry<String, JsonElement> e : all.entrySet()) {
            if (!e.getKey().equals(project) && e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has(name)) return true;
        }
        return false;
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A cursor picture a page or stylesheet asks for: cursor: url(name.png). */
    private static final java.util.regex.Pattern CURSOR_URL = java.util.regex.Pattern.compile(
            "cursor\\s*:[^;}]*?url\\(\\s*['\"]?([^'\")]+)", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * The pictures among [files] that one of its pages or stylesheets uses as a cursor, copied into the
     * cursors folder, where the resource pack takes its cursors from. Returns the names of those that are
     * not in the pack yet (it is built when the server starts) or have changed.
     */
    private List<String> cursors(Map<String, byte[]> files) throws IOException {
        java.util.Set<String> asked = new java.util.HashSet<>();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            if (!f.getKey().endsWith(".html") && !f.getKey().endsWith(".css")) continue;
            java.util.regex.Matcher m = CURSOR_URL.matcher(new String(f.getValue(), StandardCharsets.UTF_8));
            while (m.find()) {
                String url = m.group(1).trim();
                asked.add(url.substring(url.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT));
            }
        }
        List<String> added = new ArrayList<>();
        if (asked.isEmpty()) return added;
        Path dir = plugin.getDataFolder().toPath().resolve("cursors");
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            String file = f.getKey().toLowerCase(java.util.Locale.ROOT);
            if (!file.endsWith(".png") || !asked.contains(file)) continue;
            Files.createDirectories(dir);
            Path target = dir.resolve(file);
            boolean same = Files.isRegularFile(target) && java.util.Arrays.equals(Files.readAllBytes(target), f.getValue());
            if (!same) Files.write(target, f.getValue());
            String name = file.substring(0, file.length() - ".png".length());
            if (!same || ai.resourcepack.ui.framework.api.Pointer.custom(name) == null) added.add(name);
        }
        return added;
    }

    /** The shared files by name, checked and decoded; anything off and nothing is written. */
    private static Map<String, byte[]> files(JsonArray list) throws IOException {
        if (list == null || list.isEmpty()) throw new IOException("it has no files");
        if (list.size() > MAX_FILES) throw new IOException("it has too many files");
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (JsonElement element : list) {
            JsonObject file = element.getAsJsonObject();
            String name = file.get("name").getAsString();
            String content = file.get("content").getAsString();
            if (!NAME.matcher(name).matches()) throw new IOException("it has a file called '" + name + "', which this server will not write");
            if (name.endsWith(".html") || name.endsWith(".css")) {
                out.put(name, content.getBytes(StandardCharsets.UTF_8));
            } else {
                var m = PICTURE.matcher(content);
                if (!m.matches()) throw new IOException(name + " is not a picture");
                out.put(name, Base64.getDecoder().decode(m.group(1)));
            }
        }
        return out;
    }

    private static void say(CommandSender to, String text, NamedTextColor color) {
        to.sendMessage(Component.text(text, color));
    }
}
