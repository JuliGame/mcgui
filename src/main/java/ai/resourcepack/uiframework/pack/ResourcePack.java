package ai.resourcepack.uiframework.pack;

import ai.resourcepack.uiframework.Settings;
import ai.resourcepack.uiframework.api.Pointer;
import ai.resourcepack.uiframework.render.Palette;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the resource pack (the map decoder, the seated-only HUD hiding, the pointer font), serves it
 * over plain HTTP if asked, and tracks which players have it loaded, so screens know whether to send
 * them hi-colour maps or plain ones.
 */
public final class ResourcePack implements AutoCloseable {
    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("uiframework:pack".getBytes(StandardCharsets.UTF_8));
    public static final Key POINTER_FONT = Key.key("uiframework", "pointer");
    /** 26.2's pack format; 26.3 is 97.1, and an integer max_format of 97 covers every 97.x. */
    static final int FORMAT_26_2 = 88;
    static final int FORMAT_26_3 = 97;
    static final String OVERLAY_26_2 = "uiframework_26_2";

    private final Settings settings;
    private final Logger logger;
    public final byte[] bytes;
    public final String sha1;
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    private HttpServer http;

    public ResourcePack(Settings settings, Palette palette, Path dataFolder, Logger logger) throws IOException {
        this.settings = settings;
        this.logger = logger;
        this.bytes = build(palette);
        this.sha1 = sha1(bytes);
        Files.createDirectories(dataFolder);
        Files.write(dataFolder.resolve("pack.zip"), bytes);
        logger.info("Resource pack built: " + bytes.length + " bytes, sha1 " + sha1 + " (also written to pack.zip)");
    }

    public void start() throws IOException {
        if (settings.packMode != Settings.PackMode.SERVE) return;
        HttpServer server = HttpServer.create(new InetSocketAddress(settings.packBind, settings.packPort), 16);
        server.createContext("/", ex -> {
            try (ex) {
                // Only one file exists; anything else is a probe.
                if (!"GET".equals(ex.getRequestMethod()) || !"/pack.zip".equals(ex.getRequestURI().getPath())) {
                    ex.sendResponseHeaders(404, -1);
                    return;
                }
                ex.getResponseHeaders().add("Content-Type", "application/zip");
                ex.getResponseHeaders().add("Cache-Control", "no-store");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(bytes);
                }
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "UIFramework-pack");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        http = server;
        logger.info("Serving the resource pack on " + settings.packBind + ":" + settings.packPort);
    }

    private String url() {
        return switch (settings.packMode) {
            case OFF -> null;
            case EXTERNAL -> settings.packPublicUrl.isBlank() ? null : settings.packPublicUrl;
            case SERVE -> settings.packPublicUrl.isBlank() ? "http://127.0.0.1:" + settings.packPort + "/pack.zip" : settings.packPublicUrl;
        };
    }

    public void send(Player player) {
        String url = url();
        if (url == null) return;
        // The sha1 in the query defeats any cache between the client and the server.
        String sep = url.contains("?") ? "&" : "?";
        player.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(url + sep + "v=" + sha1), sha1))
                .required(settings.packRequired)
                .prompt(Component.text(settings.packPrompt))
                .replace(false));
    }

    public boolean has(UUID player) {
        return loaded.contains(player);
    }

    public void markLoaded(UUID player) {
        loaded.add(player);
    }

    public void forget(UUID player) {
        loaded.remove(player);
    }

    @Override
    public void close() {
        if (http != null) http.stop(0);
        http = null;
    }

    private static byte[] build(Palette palette) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            Entries e = new Entries(zip);
            e.put("pack.mcmeta", """
                    {
                      "pack": {"description": "UI Framework", "min_format": %d, "max_format": %d},
                      "overlays": {"entries": [{"directory": "%s", "min_format": %d, "max_format": %d}]}
                    }
                    """.formatted(FORMAT_26_2, FORMAT_26_3, OVERLAY_26_2, FORMAT_26_2, FORMAT_26_3 - 1));
            for (Shaders.Dialect d : Shaders.Dialect.values()) {
                String root = (d == Shaders.Dialect.V26_3 ? "" : OVERLAY_26_2 + "/") + "assets/minecraft/shaders/core/";
                e.put(root + "text.vsh", Shaders.textVsh(d, palette));
                e.put(root + "text.fsh", Shaders.textFsh(d, palette));
                e.put(root + "position_tex_color.vsh", Shaders.positionTexColorVsh(d));
                e.put(root + "position_tex_color.fsh", Shaders.positionTexColorFsh(d));
            }
            // The pointer a seated player sees: one glyph per shape on a text display in front of the
            // view. Text, unlike an item, gets no directional shading, so the arrow stays white.
            StringBuilder chars = new StringBuilder();
            for (Pointer p : Pointer.values()) chars.append("\\u").append(String.format("%04x", (int) p.glyph));
            e.put("assets/uiframework/font/pointer.json", """
                    {"providers": [{"type": "bitmap", "file": "uiframework:font/pointer.png", "ascent": 7, "height": 16, "chars": ["%s"]}]}
                    """.formatted(chars));
            e.put("assets/uiframework/textures/font/pointer.png", png(pointerSheet()));
        }
        return out.toByteArray();
    }

    /** Every pointer glyph side by side, the font's one row. */
    static BufferedImage pointerSheet() {
        Pointer[] all = Pointer.values();
        BufferedImage sheet = new BufferedImage(16 * all.length, 16, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < all.length; i++) {
            String[] art = all[i].art;
            for (int y = 0; y < art.length; y++) {
                for (int x = 0; x < art[y].length(); x++) {
                    char c = art[y].charAt(x);
                    if (c == 'X') sheet.setRGB(i * 16 + x, y, 0xFF000000);
                    else if (c == 'o') sheet.setRGB(i * 16 + x, y, 0xFFFFFFFF);
                }
            }
        }
        return sheet;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Zip entries with a fixed timestamp, so the zip (and its sha1) is identical across restarts and clients keep their copy. */
    private record Entries(ZipOutputStream zip) {
        void put(String name, byte[] data) throws IOException {
            ZipEntry entry = new ZipEntry(name);
            entry.setTime(0);
            zip.putNextEntry(entry);
            zip.write(data);
            zip.closeEntry();
        }

        void put(String name, String text) throws IOException {
            put(name, text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
