package ai.resourcepack.ui;

import ai.resourcepack.ui.framework.Framework;
import ai.resourcepack.ui.framework.FrameworkCommand;
import ai.resourcepack.ui.framework.api.UIFramework;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * UI Engine: HTML pages from {@code plugins/UIEngine/pages/}, shown in game on wall screens and in
 * fullscreen views. No browser: the pages are laid out and painted by the plugin's own renderer, so
 * what works is static HTML and CSS (no JavaScript), with links, commands and hover states made to
 * work in game. Underneath is the {@link Framework}, which puts pictures on walls and in views and is
 * there for other plugins to draw with too ({@link UIFramework#get()}).
 */
public final class UIEnginePlugin extends JavaPlugin {
    /** The example pages copied into a new pages folder. */
    private static final String[] EXAMPLES = {"menu.html", "rules.html", "shop.html", "style.css", "market.html",
            "market-overlay.html", "market-blur.html",
            "market/awning_cloth.png", "market/coin_pouch.png", "market/dawn_apple.png", "market/echo_tome.png", "market/ember_blade.png",
            "market/ember_blade_3d.png", "market/emerald_chest.png", "market/emerald_coin.png", "market/frost_bow.png",
            "market/geode_heart.png", "market/glow_lantern.png", "market/glow_lantern_3d.png", "market/market_stall.png",
            "market/night_planks.png", "market/phoenix_feather.png", "market/sky_boots.png", "market/starlight_potion.png",
            "market/starlight_potion_3d.png", "market/void_pearl.png", "market/warden_aegis.png"};

    private Framework framework;
    private volatile PageStore pages;
    private Screens screens;
    private Renders renderers;
    private Executor serverThread;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private BukkitTask watcher;
    private FileConfiguration cfg;
    private volatile FirstPictures pictures;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cfg = getConfig();
        framework = new Framework(this);
        if (!framework.enable()) {
            framework = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Executor main = UIFramework.get().serverThread();
        serverThread = task -> main.execute(() -> {
            if (isEnabled()) task.run();
        });
        renderers = new Renders();
        pages = newPageStore();
        pictures = newFirstPictures();
        screens = new Screens(this, renderers);
        Heads heads = new Heads(this, screens::redrawSchemed);
        Page.SCHEMES.put("head", heads::image);
        getServer().getPluginManager().registerEvents(screens, this);
        // Warm the renderer so the first page a player opens is quick: fonts, then a few pages loaded and drawn
        // once, full screen, off screen. A cold JVM takes several times longer over its first pictures. Then the
        // first picture of every placed screen's page, kept so its first viewers are sent it without waiting.
        renderers.execute(() -> {
            ai.resourcepack.ui.html.text.Fonts.bundled();
            warmUp();
        });
        screens.load();
        UiCommand command = new UiCommand(this, new FrameworkCommand(framework));
        getCommand("ui").setExecutor(command);
        getCommand("ui").setTabCompleter(command);
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> screens.tick(Bukkit.getCurrentTick()), 1, 1));
        watch();
        UIEngine.instance = this;
    }

    @Override
    public void onDisable() {
        UIEngine.instance = null;
        Page.SCHEMES.remove("head");
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        if (watcher != null) watcher.cancel();
        if (screens != null) screens.shutdown();
        if (pages != null) pages.close();
        if (renderers != null) {
            try {
                renderers.close(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Last: the pages above draw through it to the end.
        if (framework != null) framework.disable();
        HandlerList.unregisterAll(this);
    }

    /** The pages folder the config names (made, with the examples, if it is not there), with a loader as the config says. */
    private PageStore newPageStore() {
        Path folder = getDataFolder().toPath().resolve(cfg.getString("pages-folder", "pages")).normalize();
        try {
            if (!Files.isDirectory(folder)) {
                Files.createDirectories(folder);
                for (String name : EXAMPLES) copyExample(name, folder);
            }
        } catch (IOException e) {
            getLogger().warning("Could not create the pages folder: " + e.getMessage());
        }
        Page.Loader loader = new Page.Loader(cfg.getBoolean("allow-web", true), getDataFolder().toPath().resolve("cache"),
                cfg.getBoolean("allow-private-hosts", false), folder);
        PageStore store = new PageStore(folder, loader, getLogger());
        store.onReload = name -> serverThread.execute(() -> screens.reloaded(name));
        return store;
    }

    /** Starts (or restarts, or stops) the live-reload check, as the config says. */
    private void watch() {
        if (watcher != null) watcher.cancel();
        watcher = cfg.getBoolean("live-reload", true)
                ? Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> pages.checkForChanges(), 20, 20) : null;
    }

    /**
     * Reads config.yml and the pages again, keeping placed screens. The page store and its loader are
     * made anew, so pages-folder, allow-web and allow-private-hosts apply, and URLs that failed are
     * tried again.
     */
    void reload() {
        reloadConfig();
        cfg = getConfig();
        PageStore old = pages;
        pages = newPageStore();
        old.close();
        // Every page is read again, as a new revision: no picture of the old ones is any use, and the bounds may have changed.
        pictures = newFirstPictures();
        Placeholders.reset();
        watch();
        for (String name : pages.names()) screens.reloaded(name);
    }

    private void copyExample(String name, Path folder) throws IOException {
        try (InputStream in = getResource("pages/" + name)) {
            if (in == null) return;
            Path to = folder.resolve(name);
            Files.createDirectories(to.getParent());
            Files.copy(in, to);
        }
    }

    PageStore pages() {
        return pages;
    }

    /** The first pictures of pages, kept for the next session to show them (see {@link FirstPictures}). */
    FirstPictures firstPictures() {
        return pictures;
    }

    private FirstPictures newFirstPictures() {
        return new FirstPictures(cfg.getInt("first-pictures.entries", 64), Math.round(cfg.getDouble("first-pictures.megabytes", 128) * 1048576));
    }

    /**
     * Runs a task on the server thread as soon as it is free, which between ticks is at once (the Bukkit
     * scheduler would hold it for the next tick), and not at all once the plugin is disabled.
     */
    Executor serverThread() {
        return serverThread;
    }

    Screens screens() {
        return screens;
    }

    boolean debug() {
        return cfg.getBoolean("debug", false);
    }

    /** How long one picture may take to draw (ms) before it is given up and reported like any page that cannot be drawn; 0 for no limit. */
    long renderTimeout() {
        return Math.max(0, Math.round(cfg.getDouble("render-timeout", HtmlRenderer.DEFAULT_TIMEOUT_MS / 1000.0) * 1000));
    }

    /** The sound of a click that does something: a sound, or none. true (the default) is the game's button click. */
    String clickSound() {
        Object v = cfg.get("click-sound", true);
        if (v instanceof Boolean b) return b ? Sounds.CLICK : "none";
        return String.valueOf(v);
    }

    /** Ticks between the pictures of a page while something on it animates: 20 / animation-fps. */
    int animationTicks() {
        return Math.max(1, (int) Math.round(20.0 / Math.max(1, Math.min(20, cfg.getInt("animation-fps", 10)))));
    }

    /** CSS pixels a notch scrolls, or "tile" for one map row. */
    String scrollStep() {
        return cfg.getString("scroll-step", "80");
    }

    /** Whether a scroll by whole map rows moves the canvas, so viewers are sent only the rows that came in. */
    boolean tileShift() {
        return cfg.getBoolean("tile-shift", true);
    }

    /** Hover targets near a resting pointer whose repaint is drawn in advance, while the cpu-threads pool is idle; 0 for none. */
    int hoverAhead() {
        return Math.max(0, Math.min(8, cfg.getInt("hover-ahead", 2)));
    }

    boolean wallScroll() {
        return cfg.getBoolean("walls.scroll-wheel", true);
    }

    /** Whether right-clicking a wall screen opens its page fullscreen (else it clicks, like the left button). */
    boolean wallFullscreen() {
        return cfg.getBoolean("walls.right-click-fullscreen", true);
    }

    /** A wall's design height when its page does not say: so many CSS pixels per map row. */
    double wallHeight(int rows) {
        return rows * cfg.getDouble("walls.pixels-per-map", 128);
    }

    double viewHeight() {
        return cfg.getDouble("views.height", 576);
    }

    /** Pages {@link #warmUp} loads and draws when the plugin starts. */
    private static final int WARM_PAGES = 3, WARM_ROUNDS = 3;

    /**
     * Loads the first few pages and draws each a few times at the default view size, so the JIT has
     * compiled the layout, paint and conversion code before a player waits on it. Runs on a render thread.
     */
    private void warmUp() {
        try {
            long t0 = System.nanoTime();
            int done = 0;
            for (String name : pages.names()) {
                if (done == WARM_PAGES || !isEnabled()) break;
                Page page = pages.load(name).get(30, java.util.concurrent.TimeUnit.SECONDS);
                if (page == null) continue;
                int[] size = viewSize(page);
                var canvas = UIFramework.get().canvas(size[0], size[1]);
                double scale = canvas.height() / Screens.designHeight(page, viewHeight());
                // A copy: the page's own document is copied by sessions on other render threads meanwhile, and drawing
                // fills in jsoup's child lists, which a copy being made at the same moment could read half written.
                var doc = page.document.clone();
                for (int i = 0; i < WARM_ROUNDS; i++) {
                    var frame = ai.resourcepack.ui.html.HtmlRenderer.render(page, doc, canvas.width(), canvas.height(), scale, i * 40.0,
                            ai.resourcepack.ui.html.css.Selector.State.NONE);
                    canvas.draw(frame.image(), 0, 0);
                }
                done++;
            }
            if (debug()) getLogger().info("Warmed up on " + done + " pages in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        } catch (Exception | LinkageError e) {
            // Only ever a head start: a page that will not load or draw here fails for the player too, with a message.
            if (debug()) getLogger().info("Warm-up stopped: " + e);
        }
        warmFirstPictures();
    }

    /**
     * Draws the first picture of every placed screen's page at the screen's size and fullscreen, and of the pages
     * {@link #warmUp} drew, fullscreen, and keeps each in {@link FirstPictures}: so the first viewer of each after a
     * restart is sent the picture at once. Each is drawn exactly as a session would draw it, filled for nobody in
     * particular, which is what any viewer's copy is unless the page is filled with their own values (then a session
     * never takes a kept picture anyway). Runs on a render thread, after the screens are placed.
     */
    private void warmFirstPictures() {
        FirstPictures kept = pictures;
        if (!kept.enabled()) return;
        long t0 = System.nanoTime();
        record Target(String name, int cols, int rows, double height) {}
        java.util.Set<Target> targets = new java.util.LinkedHashSet<>();
        // Fullscreen ones are 0 x 0 here: their size is the page's own, known once it is loaded.
        for (Screens.Wall w : screens.walls()) {
            String name = pages.normalise(w.page);
            if (name == null) continue;
            targets.add(new Target(name, w.cols, w.rows, w.height > 0 ? w.height : wallHeight(w.rows)));
            targets.add(new Target(name, 0, 0, viewHeight()));
        }
        List<String> names = pages.names();
        for (int i = 0; i < Math.min(WARM_PAGES, names.size()); i++) targets.add(new Target(names.get(i), 0, 0, viewHeight()));
        int drawn = 0;
        for (Target t : targets) {
            if (!isEnabled() || drawn >= cfg.getInt("first-pictures.entries", 64)) break;
            try {
                Page page = pages.load(t.name).get(30, TimeUnit.SECONDS);
                if (page == null) continue;
                int[] size = t.cols > 0 ? new int[]{t.cols, t.rows} : viewSize(page);
                double scale = size[1] * 128 / Screens.designHeight(page, t.height);
                // On the server thread, where placeholders are filled.
                Placeholders.Filled filled = java.util.concurrent.CompletableFuture.supplyAsync(() -> Placeholders.fill(page.document, null), serverThread)
                        .get(30, TimeUnit.SECONDS);
                FirstPictures.Key key = new FirstPictures.Key(t.name, page, size[0], size[1], scale, filled.key());
                if (!kept.claim(key, p -> {}).mine()) continue;
                FirstPictures.Picture picture = null;
                try {
                    var frame = HtmlRenderer.render(page, filled.copy(), size[0] * 128, size[1] * 128, scale, 0, HtmlRenderer.state(null, null),
                            null, null, renderTimeout());
                    var canvas = UIFramework.get().canvas(size[0], size[1]);
                    canvas.transparency(page.overlay());
                    canvas.tint(page.overlayTint());
                    canvas.draw(frame.image(), 0, 0);
                    picture = FirstPictures.Picture.of(key, frame, canvas);
                } finally {
                    kept.publish(key, picture);
                }
                drawn++;
            } catch (Exception | LinkageError e) {
                // That page's first viewer draws it, as without the warm-up, and hears why it cannot be drawn if it cannot.
                if (debug()) getLogger().info("Could not keep the first picture of " + t.name + ": " + e);
            }
        }
        if (debug()) getLogger().info("Kept " + drawn + " first pictures in " + (System.nanoTime() - t0) / 1_000_000 + " ms (" + kept.stats() + ")");
    }

    /** A view's size in maps: the page's {@code <meta name="ui:size" content="12x7">}, else the config's. */
    int[] viewSize(Page p) {
        String v = p.meta("ui:size");
        if (v == null) v = cfg.getString("views.size", "16x9");
        String[] parts = v.toLowerCase().split("x");
        try {
            int c = Integer.parseInt(parts[0].trim()), r = Integer.parseInt(parts[1].trim());
            return new int[]{Math.max(1, Math.min(32, c)), Math.max(1, Math.min(18, r))};
        } catch (RuntimeException e) {
            return new int[]{16, 9};
        }
    }

}
