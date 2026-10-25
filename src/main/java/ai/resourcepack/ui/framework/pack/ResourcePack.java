package ai.resourcepack.ui.framework.pack;

import ai.resourcepack.ui.framework.Settings;
import ai.resourcepack.ui.framework.api.Pointer;
import ai.resourcepack.ui.framework.render.Palette;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds the resource pack (the map decoder, the seated-only HUD hiding, the pointer font, the server's
 * own sounds), serves it
 * over plain HTTP if asked, and tracks which players have it loaded, so screens know whether to send
 * them hi-colour maps or plain ones.
 */
public final class ResourcePack implements AutoCloseable {
    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("uiengine:pack".getBytes(StandardCharsets.UTF_8));
    public static final Key POINTER_FONT = Key.key("uiengine", "pointer");
    /** 1.21.9's pack format, the oldest the pack is for; 26.3 is 97.1, and an integer max_format of 97 covers every 97.x. */
    static final int FORMAT_OLDEST = Shaders.Dialect.V1_21_9.minFormat;
    static final int FORMAT_26_3 = Shaders.Dialect.V26_3.maxFormat;

    private final Settings settings;
    private final Logger logger;
    public final byte[] bytes;
    public final String sha1;
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    private HttpServer http;
    /** The server's threads: HttpServer.stop() leaves an executor it was given running. */
    private ExecutorService executor;

    public ResourcePack(Settings settings, Palette palette, Path dataFolder, Logger logger) throws IOException {
        this.settings = settings;
        this.logger = logger;
        Path sounds = dataFolder.resolve("sounds");
        Files.createDirectories(sounds);
        Path cursors = dataFolder.resolve("cursors");
        Files.createDirectories(cursors);
        loadCursors(cursors, logger);
        this.bytes = build(palette, sounds, logger);
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
        // The server reads each request on one of these threads, so a few connections that stall
        // part-way must not take every one: up to 8 at once, each ending after 30 s idle. A pool only
        // grows past its core size once its queue is full, so the core size is the cap.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(8, 8, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "MCGUI-pack");
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        executor = pool;
        server.setExecutor(pool);
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
        // Added beside any other plugin's pack, never in place of it. Bukkit's call, which both servers have.
        player.addResourcePack(PACK_ID, url + sep + "v=" + sha1, HexFormat.of().parseHex(sha1), settings.packPrompt, settings.packRequired);
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
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    private static byte[] build(Palette palette, Path sounds, Logger logger) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            Entries e = new Entries(zip);
            // 26.3's shaders are the pack's own; every older version's are in an overlay for its formats, which replaces
            // each of them (a version before 26.2 also reads its text shader by another name, rendertype_text).
            List<String> overlays = new ArrayList<>();
            for (Shaders.Dialect d : Shaders.Dialect.values()) {
                if (d.overlay() != null) overlays.add("""
                        {"directory": "%s", "min_format": %d, "max_format": %d}""".formatted(d.overlay(), d.minFormat, d.maxFormat));
            }
            e.put("pack.mcmeta", """
                    {
                      "pack": {"description": "MC GUI", "min_format": %d, "max_format": %d},
                      "overlays": {"entries": [%s]}
                    }
                    """.formatted(FORMAT_OLDEST, FORMAT_26_3, String.join(", ", overlays)));
            for (Shaders.Dialect d : Shaders.Dialect.values()) {
                String assets = (d.overlay() == null ? "" : d.overlay() + "/") + "assets/";
                String root = assets + "minecraft/shaders/core/";
                e.put(root + d.textShader() + ".vsh", Shaders.textVsh(d, palette));
                e.put(root + d.textShader() + ".fsh", Shaders.textFsh(d, palette));
                e.put(root + "position_tex_color.vsh", Shaders.positionTexColorVsh(d));
                e.put(root + "position_tex_color.fsh", Shaders.positionTexColorFsh(d));
                // The world round an overlay's panel, kept still: passes of the outline post effect, which a glowing
                // display switches on.
                String post = assets + "uiengine/shaders/post/";
                e.put(post + "overlay_take.fsh", Shaders.overlayTakeFsh(d));
                e.put(post + "overlay_blur.fsh", Shaders.overlayBlurFsh(d));
                e.put(post + "overlay_mask.fsh", Shaders.overlayMaskFsh(d));
                e.put(post + "overlay_compose.fsh", Shaders.overlayComposeFsh(d));
                e.put(post + "overlay_outline.fsh", Shaders.overlayOutlineFsh(d));
                // Each pass draws over only as much of the window as it writes (nothing for most frames).
                e.put(post + "overlay_take.vsh", Shaders.overlayTakeVsh(d));
                e.put(post + "overlay_blur.vsh", Shaders.overlayBlurVsh(d));
                e.put(post + "overlay_signed_main.vsh", Shaders.overlaySignedVsh(d, "MainSampler"));
                e.put(post + "overlay_signed_in.vsh", Shaders.overlaySignedVsh(d, "InSampler"));
                // The pointer taken out of the menu blur, so the pause menu shows only the system's cursor.
                e.put(post + "pointer_find.fsh", Shaders.pointerFindFsh(d));
                e.put(post + "pointer_grow.fsh", Shaders.pointerGrowFsh(d));
                e.put(post + "pointer_fill.fsh", Shaders.pointerFillFsh(d));
            }
            // The two post effects read the same in both versions, so the 26.2 overlay needs no copy.
            e.put("assets/minecraft/post_effect/entity_outline.json", Shaders.overlayChain());
            e.put("assets/minecraft/post_effect/blur.json", Shaders.menuBlurChain());
            // 26.2 draws clouds after the outline effect, over the still world: none for an overlay's camera there. So for
            // 1.21.11 and 26.1, whose clouds shader is 26.2's; 1.21.9's is another, and it has no overlays to keep still.
            for (Shaders.Dialect d : List.of(Shaders.Dialect.V1_21_11, Shaders.Dialect.V26_1, Shaders.Dialect.V26_2)) {
                e.put(d.overlay() + "/assets/minecraft/shaders/core/rendertype_clouds.vsh", Shaders.cloudsVsh());
            }
            // The pointer a seated player sees: one glyph per shape on a text display in front of the
            // view. Text, unlike an item, gets no directional shading, so the arrow stays white.
            // The server's own cursors (Pointer.custom) are a provider each, as tall as they are drawn, with the
            // same ascent, so their top is where the arrow's is and a hot pixel counts from the same corner.
            StringBuilder chars = new StringBuilder();
            for (Pointer p : Pointer.values()) chars.append("\\u").append(String.format("%04x", (int) p.glyph));
            List<String> providers = new ArrayList<>();
            providers.add("""
                    {"type": "bitmap", "file": "uiengine:font/pointer.png", "ascent": %d, "height": 16, "chars": ["%s"]}"""
                    .formatted(Pointer.ASCENT, chars));
            for (Pointer p : Pointer.customs()) {
                providers.add("""
                        {"type": "bitmap", "file": "uiengine:font/cursor/%s.png", "ascent": %d, "height": %d, "chars": ["\\u%04x"]}"""
                        .formatted(p.name, Pointer.ASCENT, p.height(), (int) p.glyph));
                e.put("assets/uiengine/textures/font/cursor/" + p.name + ".png", png(p.image()));
            }
            e.put("assets/uiengine/font/pointer.json", "{\"providers\": [\n  " + String.join(",\n  ", providers) + "\n]}\n");
            e.put("assets/uiengine/textures/font/pointer.png", png(pointerSheet()));
            soundFiles(e, sounds, logger);
        }
        return out.toByteArray();
    }

    /** A cursor's name: its file name in lower case, letters, digits, {@code _ -}: a texture path and a CSS name alike. */
    private static final Pattern CURSOR_NAME = Pattern.compile("[a-z0-9_-]+");
    /** The widest and tallest picture a cursor may be, in its own pixels. */
    private static final int CURSOR_MAX_SIDE = 128;

    /**
     * Every .png directly in [dir] (the cursors folder), as {@link Pointer#custom} by its file name:
     * {@code cursors/sword.png} is the cursor {@code sword}, which a page asks for with
     * {@code cursor: url(sword.png)}. Sorted, so each has the same glyph while the folder is the same.
     */
    private static void loadCursors(Path dir, Logger logger) throws IOException {
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")).sorted().toList();
        }
        List<String> loaded = new ArrayList<>();
        for (Path f : files) {
            String file = f.getFileName().toString();
            String name = file.substring(0, file.length() - ".png".length()).toLowerCase(Locale.ROOT);
            if (!CURSOR_NAME.matcher(name).matches()) {
                logger.warning("Cursor " + file + " left out: a cursor's name may only have letters, digits, _ and -");
                continue;
            }
            BufferedImage image;
            try {
                image = ImageIO.read(f.toFile());
            } catch (IOException ex) {
                image = null;
            }
            if (image == null) {
                logger.warning("Cursor " + file + " left out: it is not a picture this server can read");
                continue;
            }
            if (image.getWidth() > CURSOR_MAX_SIDE || image.getHeight() > CURSOR_MAX_SIDE) {
                logger.warning("Cursor " + file + " left out: it is " + image.getWidth() + "x" + image.getHeight() + ", more than " + CURSOR_MAX_SIDE + " a side");
                continue;
            }
            BufferedImage argb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            argb.getGraphics().drawImage(image, 0, 0, null);
            if (Pointer.load(name, argb) == null) {
                logger.warning("Cursor " + file + " left out: " + (image.getHeight() <= Pointer.ASCENT
                        ? "a cursor must be at least " + (Pointer.ASCENT + 1) + " pixels tall" : "the pack holds " + Pointer.MAX_CUSTOM + " cursors at most"));
                continue;
            }
            loaded.add(name);
        }
        if (!loaded.isEmpty()) logger.info("Cursors in the pack: " + String.join(", ", loaded) + " (a page asks for one with cursor: url(<name>.png))");
    }

    /** A sound's name: lower case, digits, {@code _ - . /}, as the game takes in a resource location. */
    private static final Pattern SOUND_NAME = Pattern.compile("[a-z0-9_./-]+");

    /**
     * Every .ogg under [dir] (the sounds folder), as a sound event of its path without ".ogg":
     * {@code sounds/menu/open.ogg} is {@code uiengine:menu/open}, for /playsound and for any plugin.
     * Sorted, so the pack is the same bytes while the folder is.
     */
    private static void soundFiles(Entries e, Path dir, Logger logger) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().endsWith(".ogg")).sorted().toList();
        }
        if (files.isEmpty()) return;
        List<String> events = new ArrayList<>();
        for (Path f : files) {
            String name = dir.relativize(f).toString().replace(File.separatorChar, '/');
            name = name.substring(0, name.length() - ".ogg".length());
            if (!SOUND_NAME.matcher(name).matches()) {
                logger.warning("Sound " + name + ".ogg left out: a sound's name may only have lower case letters, digits and _ - . /");
                continue;
            }
            e.put("assets/uiengine/sounds/" + name + ".ogg", Files.readAllBytes(f));
            events.add("  \"" + name + "\": {\"sounds\": [\"uiengine:" + name + "\"]}");
        }
        if (events.isEmpty()) return;
        e.put("assets/uiengine/sounds.json", "{\n" + String.join(",\n", events) + "\n}\n");
        logger.info("Sounds in the pack: " + events.size() + " (uiengine:<name>)");
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
