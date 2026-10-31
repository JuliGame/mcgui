package ai.resourcepack.ui.framework.pack;

import ai.resourcepack.common.host.PackUploader;
import ai.resourcepack.ui.framework.Settings;
import ai.resourcepack.ui.framework.api.Pointer;
import ai.resourcepack.ui.framework.render.Palette;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Builds the resource pack (the map decoder, the seated-only HUD hiding, the pointer font, the server's
 * own sounds), puts it on ResourcePack AI's pack host or serves it over plain HTTP, and tracks which players have it loaded, so screens know whether to send
 * them hi-colour maps or plain ones.
 */
public final class ResourcePack implements AutoCloseable {
    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("uiengine:pack".getBytes(StandardCharsets.UTF_8));
    public static final Key POINTER_FONT = Key.key("uiengine", "pointer");
    /** 1.21.11's pack format, the oldest the pack is for; 26.3 is 97.1, and an integer max_format of 97 covers every 97.x. */
    static final int FORMAT_OLDEST = Shaders.Dialect.V1_21_11.minFormat;
    static final int FORMAT_26_3 = Shaders.Dialect.V26_3.maxFormat;

    private final Settings settings;
    private final Logger logger;
    public final byte[] bytes;
    public final String sha1;
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    /** Merged elsewhere: players a pack was declined by (true) or failed for (false), with none loaded since. */
    private final Map<UUID, Boolean> refused = new ConcurrentHashMap<>();
    /** Players whose game loaded a sibling plugin's pack after ours (see {@link #markBuried}): ours is no longer on top. */
    private final Set<UUID> buried = ConcurrentHashMap.newKeySet();
    /** Players ours was sent again to, to be on top again (see {@link #raise}), until it has loaded or failed. */
    private final Set<UUID> raising = ConcurrentHashMap.newKeySet();
    /** How often a running server tells the pack host it still uses the pack, which forgets one untouched for 30 days. */
    private static final long TOUCH_HOURS = PackUploader.TOUCH_HOURS;

    /** Mode upload: the pack's link on the pack host, once it has it. */
    private volatile String hosted;
    private ScheduledExecutorService uploads;
    private volatile HttpServer http;
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
        // ItemsAdder merges another plugin's pack from a folder, never a zip (merge_other_plugins_resourcepacks_folders).
        if (settings.packMode == Settings.PackMode.EXTERNAL) unpack(bytes, dataFolder.resolve(FOLDER), logger);
        ai.resourcepack.ui.StartupNotes.say(logger, "Pack", "Built, " + Math.max(1, bytes.length / 1024) + " KB (pack.zip)",
                "Resource pack built: " + bytes.length + " bytes, sha1 " + sha1 + " (also written to pack.zip)");
    }

    /**
     * Serves or uploads the pack, as the mode says. [agent] names MC GUI to the pack host; [onHosted] runs (on
     * another thread) once players can be sent a link they could not be before, to send it to those online.
     */
    public void start(String agent, Runnable onHosted) throws IOException {
        switch (settings.packMode) {
            case SERVE -> {
                serve();
                if (settings.packPublicUrl.isBlank()) {
                    ai.resourcepack.ui.StartupNotes.warn(logger, "Pack link", "public-url is blank, so only a player on this machine can download the pack: set it, or set mode to upload",
                            "resource-pack.public-url is blank, so players are sent http://127.0.0.1:" + settings.packPort
                                    + "/pack.zip, which only works for a player on this machine. Set public-url, or set mode to upload.");
                }
            }
            case UPLOAD -> {
                PackUploader uploader = new PackUploader(PackUploader.HOST, agent);
                uploads = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "MCGUI-upload");
                    t.setDaemon(true);
                    return t;
                });
                // Now, then every few hours: keeps the pack on the host, and tries a failed upload again.
                uploads.scheduleWithFixedDelay(() -> host(uploader, onHosted), 0, TOUCH_HOURS, TimeUnit.HOURS);
                ai.resourcepack.ui.StartupNotes.say(logger, "Hosting", "On packs.resourcepack.ai", "Hosting the resource pack on packs.resourcepack.ai");
            }
            case EXTERNAL -> ai.resourcepack.ui.StartupNotes.say(logger, "Pack", settings.packPublicUrl.isBlank()
                            ? "External: merge plugins/MCGUI/" + FOLDER + " into your pack plugin's"
                            : "External: players are sent public-url",
                    settings.packPublicUrl.isBlank()
                            ? "resource-pack.mode is external with no public-url: merge plugins/MCGUI/" + FOLDER
                                    + " (or pack.zip) into another plugin's pack, which players must load for pages to open"
                            : "resource-pack.mode is external: players are sent " + settings.packPublicUrl);
            default -> {}
        }
    }

    /** Makes sure the pack host has the pack; if it cannot, serves it from here meanwhile (port and public-url). */
    private void host(PackUploader uploader, Runnable onHosted) {
        try {
            String link = uploader.ensure(sha1, bytes, "the MC GUI pack");
            boolean first = hosted == null;
            hosted = link;
            if (first) {
                if (http != null) logger.info("The resource pack is on packs.resourcepack.ai now: players are sent it from there.");
                onHosted.run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            // Uploaded before: there for weeks yet, and the next touch is soon enough.
            if (hosted != null || http != null) return;
            try {
                serve();
                logger.warning("Could not put the resource pack on packs.resourcepack.ai (" + e.getMessage() + "), so this server is serving it on port "
                        + settings.packPort + " meanwhile" + (settings.packPublicUrl.isBlank()
                        ? ". With public-url blank, only a player on this machine can download it from there." : ".")
                        + " The upload is tried again every " + TOUCH_HOURS + " hours.");
                onHosted.run();
            } catch (IOException bind) {
                logger.warning("Could not put the resource pack on packs.resourcepack.ai (" + e.getMessage() + "), nor serve it on port "
                        + settings.packPort + " (" + bind.getMessage() + "), so players are not sent it. The upload is tried again every "
                        + TOUCH_HOURS + " hours.");
            }
        }
    }

    private synchronized void serve() throws IOException {
        if (http != null) return;
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
        ai.resourcepack.ui.StartupNotes.say(logger, "Serving", "The resource pack on " + settings.packBind + ":" + settings.packPort,
                "Serving the resource pack on " + settings.packBind + ":" + settings.packPort);
    }

    private String url() {
        return switch (settings.packMode) {
            case OFF -> null;
            case EXTERNAL -> settings.packPublicUrl.isBlank() ? null : settings.packPublicUrl;
            case SERVE -> served();
            case UPLOAD -> hosted != null ? hosted : http != null ? served() : null;
        };
    }

    private String served() {
        return settings.packPublicUrl.isBlank() ? "http://127.0.0.1:" + settings.packPort + "/pack.zip" : settings.packPublicUrl;
    }

    public void send(Player player) {
        String url = url();
        if (url == null) return;
        // The sha1 in the query defeats any cache between the client and the server.
        String sep = url.contains("?") ? "&" : "?";
        // Added beside any other plugin's pack, never in place of it. Bukkit's call, which both servers have.
        player.addResourcePack(PACK_ID, url + sep + "v=" + sha1, HexFormat.of().parseHex(sha1), settings.packPrompt, settings.packRequired);
    }

    /** Whether players are sent the pack by MC GUI: any mode but off, and external only with a public-url. */
    public boolean offers() {
        return settings.packMode != Settings.PackMode.OFF && !mergedElsewhere();
    }

    /**
     * External with no public-url: the pack is merged into another plugin's (ItemsAdder, Nexo...), which sends it under
     * its own id, so any pack a player loads counts as ours.
     */
    public boolean mergedElsewhere() {
        return settings.packMode == Settings.PackMode.EXTERNAL && settings.packPublicUrl.isBlank();
    }

    /**
     * Merged elsewhere, a player has the pack unless one was refused and none has loaded since: the other plugin may
     * send it while the player is still joining (ItemsAdder does), and Paper reports no pack status from then.
     */
    public boolean has(UUID player) {
        return loaded.contains(player) || mergedElsewhere() && !refused.containsKey(player);
    }

    /** Merged elsewhere, and a player has no pack: whether they declined it (rather than their game failing to load it). */
    public boolean declined(UUID player) {
        return Boolean.TRUE.equals(refused.get(player));
    }

    public void markLoaded(UUID player) {
        loaded.add(player);
        refused.remove(player);
    }

    /** Their game took ours (it is queued above every pack taken before it). */
    public void markAccepted(UUID player) {
        buried.remove(player);
    }

    /** A pack was declined ([declined]), or failed to download or load. */
    public void markRefused(UUID player, boolean declined) {
        if (mergedElsewhere()) {
            if (!loaded.contains(player)) refused.put(player, declined);
        } else {
            loaded.remove(player);
        }
    }

    /** Ours was taken off their game (or replaced by itself, sent again): not loaded until it says so again. */
    public void forget(UUID player) {
        loaded.remove(player);
        refused.remove(player);
    }

    /** They left. */
    public void left(UUID player) {
        forget(player);
        buried.remove(player);
        raising.remove(player);
    }

    /**
     * Another plugin of ResourcePack AI's (Dialog Engine's drawn dialogs) sent [player] a pack that their game took
     * after ours (a game takes packs in the order they are sent, each above the last, and says so as it takes them).
     * A game keeps one copy of each shader, the top pack's, and theirs replaces the text shader and
     * {@code position_tex_color} as ours does (their plugins skip ours when they merge shaders, as a sibling), so with
     * theirs on top no view of ours draws: a huge pointer glyph in the world, which turns with the mouse.
     */
    public void markBuried(UUID player) {
        if (offers()) buried.add(player);
    }

    /**
     * If a sibling's pack went over ours for [player], sends ours again: their game takes a pack sent again as new,
     * on top (it reloads its resources once, from the copy it has). True while that is under way.
     */
    public boolean raise(Player player) {
        UUID id = player.getUniqueId();
        if (buried.remove(id) && url() != null) {
            raising.add(id);
            send(player);
        }
        return raising.contains(id);
    }

    /** Whether ours, sent again by {@link #raise}, is still loading for [player]. */
    public boolean raising(UUID player) {
        return raising.contains(player);
    }

    /** Ours, sent again by {@link #raise}, has loaded or failed: nothing more to wait for. */
    public void raised(UUID player) {
        raising.remove(player);
    }

    /** Mode external: the pack unpacked, for a plugin that merges folders. Rewritten every start. */
    public static final String FOLDER = "pack";

    private static void unpack(byte[] zip, Path dir, Logger logger) throws IOException {
        Path mcmeta = dir.resolve("pack.mcmeta");
        if (Files.exists(dir)) {
            // Only ever a folder this wrote: anything else in its place is somebody's, and left alone.
            if (!Files.isRegularFile(mcmeta) || !Files.readString(mcmeta).contains("\"MC GUI\"")) {
                logger.warning("plugins/MCGUI/" + FOLDER + " is not the pack MC GUI unpacks, so it was left as it is");
                return;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(f);
            }
        }
        Path root = dir.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                Path out = root.resolve(entry.getName()).normalize();
                if (!out.startsWith(root) || entry.isDirectory()) continue;
                Files.createDirectories(out.getParent());
                Files.write(out, in.readAllBytes());
            }
        }
    }

    @Override
    public void close() {
        if (uploads != null) uploads.shutdownNow();
        uploads = null;
        if (http != null) http.stop(0);
        http = null;
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    /** The overlay directory a dialect's files are in: its own, and for 26.3 (whose files are also the root's) uiengine_26_3. */
    static String overlayOf(Shaders.Dialect d) {
        return d.overlay() != null ? d.overlay() : "uiengine_26_3";
    }

    /**
     * The pack's pack.mcmeta. 26.3's shaders are the pack's own; every older version's are in an overlay for its
     * formats, which replaces each of them (a version before 26.2 also reads its text shader by another name,
     * rendertype_text). 26.3's are in an overlay of their own as well: merged into another plugin's pack (ItemsAdder,
     * Oraxen, CraftEngine, BetterHud), the root is shared and the last copy of a file there wins, and other plugins put
     * their text shaders in overlays, which a root file never beats. Mergers list our entries after their own, and of
     * two overlays the one listed last wins.
     * <p>
     * Each entry gives its range twice, as min_format/max_format and as the old "formats". A merged pack.mcmeta holds
     * other plugins' entries too, and once any entry reaches a format below 65 (ItemsAdder's, Oraxen's and
     * BetterHud's do) the game refuses every overlay without "formats", and with that the whole pack
     * ({@code PackFormat.validateHolderList}): the player is left with no server pack at all. BetterHud also skips a
     * merged pack.mcmeta whose entries have no "formats". Alone, "formats" on a range above 64 is refused as deprecated
     * unless some entry reaches below 65, which uiengine_none does: a directory that does not exist, for a format no
     * game that loads this pack has.
     */
    static String mcmeta() {
        List<String> overlays = new ArrayList<>();
        overlays.add("""
                {"directory": "uiengine_none", "min_format": 64, "max_format": 64, "formats": [64, 64]}""");
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            overlays.add("""
                    {"directory": "%s", "min_format": %d, "max_format": %d, "formats": [%d, %d]}"""
                    .formatted(overlayOf(d), d.minFormat, d.maxFormat, d.minFormat, d.maxFormat));
        }
        return """
                {
                  "pack": {"description": "MC GUI", "min_format": %d, "max_format": %d},
                  "overlays": {"entries": [%s]}
                }
                """.formatted(FORMAT_OLDEST, FORMAT_26_3, String.join(", ", overlays));
    }

    private static byte[] build(Palette palette, Path sounds, Logger logger) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            Entries e = new Entries(zip);
            e.put("pack.mcmeta", mcmeta());
            for (Shaders.Dialect d : Shaders.Dialect.values()) {
                for (String base : d.overlay() == null ? List.of("", overlayOf(d) + "/") : List.of(overlayOf(d) + "/")) {
                    String assets = base + "assets/";
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
                    // ... and again in the improved transparency effect before 26.3, which gets the signature handed on.
                    if (d != Shaders.Dialect.V26_3) {
                        e.put(post + "overlay_relay.fsh", Shaders.overlayRelayFsh(d));
                        e.put(post + "overlay_relay.vsh", Shaders.overlayRelayVsh(d));
                    }
                    // Each pass draws over only as much of the window as it writes (nothing for most frames).
                    e.put(post + "overlay_take.vsh", Shaders.overlayTakeVsh(d));
                    e.put(post + "overlay_blur.vsh", Shaders.overlayBlurVsh(d));
                    e.put(post + "overlay_signed_main.vsh", Shaders.overlaySignedVsh(d, "MainSampler"));
                    e.put(post + "overlay_signed_in.vsh", Shaders.overlaySignedVsh(d, "InSampler"));
                    // The pointer taken out of the menu blur, so the pause menu shows only the system's cursor.
                    e.put(post + "pointer_find.fsh", Shaders.pointerFindFsh(d));
                    e.put(post + "pointer_grow.fsh", Shaders.pointerGrowFsh(d));
                    e.put(post + "pointer_fill.fsh", Shaders.pointerFillFsh(d));
                    // The two post effects every version reads the same, but for 1.21.11's outline effect, which leaves
                    // the particles drawn after it this frame's Globals. At the root for a merger that drops overlays,
                    // and in each overlay so that no other plugin's root copy beats them in a merged pack.
                    e.put(assets + "minecraft/post_effect/entity_outline.json", Shaders.overlayChain(d == Shaders.Dialect.V1_21_11));
                    e.put(assets + "minecraft/post_effect/blur.json", Shaders.menuBlurChain());
                }
            }
            // Before 26.3 clouds and the world border are drawn after the outline effect, over the still world: none
            // for an overlay's camera there (1.21.11's and 26.1's shaders for both are 26.2's). And improved
            // transparency blends translucent blocks in after it, in an effect 26.3 does not have.
            for (Shaders.Dialect d : List.of(Shaders.Dialect.V1_21_11, Shaders.Dialect.V26_1, Shaders.Dialect.V26_2)) {
                e.put(d.overlay() + "/assets/minecraft/shaders/core/rendertype_clouds.vsh", Shaders.cloudsVsh());
                e.put(d.overlay() + "/assets/minecraft/shaders/core/rendertype_world_border.vsh", Shaders.worldBorderVsh());
                e.put(d.overlay() + "/assets/minecraft/post_effect/transparency.json", Shaders.transparencyChain());
            }
            // See-through text draws over a seated camera's view (no depth test): none there. 26.2 and 26.3 have it in
            // text.vsh (and 26.2's background in text_background.vsh); before 26.2 each is a shader of its own.
            for (Shaders.Dialect d : List.of(Shaders.Dialect.V1_21_11, Shaders.Dialect.V26_1)) {
                String core = d.overlay() + "/assets/minecraft/shaders/core/";
                e.put(core + "rendertype_text_see_through.vsh", Shaders.seeThroughVsh(true));
                e.put(core + "rendertype_text_intensity_see_through.vsh", Shaders.seeThroughVsh(true));
                e.put(core + "rendertype_text_background_see_through.vsh", Shaders.seeThroughVsh(false));
            }
            e.put(Shaders.Dialect.V26_2.overlay() + "/assets/minecraft/shaders/core/text_background.vsh", Shaders.textBackgroundVsh());
            // 1.21.11 draws particles after it too (and reads its Globals where overlayChain(true) leaves them).
            e.put(Shaders.Dialect.V1_21_11.overlay() + "/assets/minecraft/shaders/core/particle.vsh", Shaders.particleVsh());
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
                e.put("assets/uiengine/textures/font/cursor/" + p.name + ".png", png(faint(p.image())));
            }
            e.put("assets/uiengine/font/pointer.json", "{\"providers\": [\n  " + String.join(",\n  ", providers) + "\n]}\n");
            e.put("assets/uiengine/textures/font/pointer.png", png(faint(pointerSheet())));
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

    /**
     * A copy of a pointer glyph sheet with every alpha at {@link Shaders#POINTER_ALPHA} 255ths of itself, rounded up so
     * the client measures each glyph as wide as before: text.fsh scales it back for the pointer, and any other text
     * shader (a shader pack's) leaves it under its alpha cut-off.
     */
    static BufferedImage faint(BufferedImage image) {
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y), a = argb >>> 24;
                out.setRGB(x, y, (a * Shaders.POINTER_ALPHA + 254) / 255 << 24 | argb & 0xFFFFFF);
            }
        }
        return out;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Buffered in memory: ImageIO.write to a plain stream buffers through a file in the temp folder, and a
        // host's full /tmp then stops the pack building with "No space left on device".
        try (ImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            if (!ImageIO.write(image, "png", ios)) throw new IOException("This Java has no PNG writer");
        }
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
