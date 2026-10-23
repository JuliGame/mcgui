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
 * {@code /ui import <code>}: the pages somebody shared from <a href="https://uiengine.dev">uiengine.dev</a>,
 * written into the pages folder. The code names a frozen copy there; this fetches its files (pages,
 * stylesheets, pictures) as JSON from {@code import-url} + code, checks every name itself (a server
 * trusts no website to keep names inside its folder), and writes them, replacing files of the same
 * name. Live reload then shows them, and the sender is offered the page to open.
 */
final class PageImport {
    /** What a shared code looks like: eight letters and digits, none that read as each other. */
    static final Pattern CODE = Pattern.compile("[a-hjkmnp-z2-9]{8}");
    /** A file name the builder may share: lower case, no folders, a page, a stylesheet or a picture. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}\\.(?:html|css|png|jpe?g|gif|svg)");
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
            say(sender, "That isn't a uiengine.dev code: it's the eight letters after /ui import on the Send to server screen.", NamedTextColor.RED);
            return;
        }
        say(sender, "Fetching " + key + "...", NamedTextColor.GRAY);
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + key))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "UIEngine/" + plugin.getPluginMeta().getVersion())
                .GET()
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).whenComplete((res, error) -> {
            List<String> written = new ArrayList<>();
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
                plugin.getLogger().info(sender.getName() + " imported " + key + " from uiengine.dev: " + String.join(", ", written));
                say(sender, "Imported " + String.join(", ", written) + ".", NamedTextColor.GREEN);
                if (open != null) {
                    sender.sendMessage(Component.text("Open it: ", NamedTextColor.GRAY)
                            .append(Component.text("/ui open " + open, NamedTextColor.AQUA)
                                    .clickEvent(ClickEvent.runCommand("/ui open " + open))
                                    .hoverEvent(HoverEvent.showText(Component.text("Click to open it fullscreen")))));
                }
            });
        });
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
