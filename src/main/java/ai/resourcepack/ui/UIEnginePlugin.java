package ai.resourcepack.ui;

import ai.resourcepack.uiframework.api.UIFramework;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UI Engine: HTML pages from {@code plugins/UIEngine/pages/}, shown in game on wall screens and in
 * fullscreen views, through UI Framework. No browser: the pages are laid out and painted by the
 * plugin's own renderer, so what works is static HTML and CSS (no JavaScript), with links,
 * commands and hover states made to work in game.
 */
public final class UIEnginePlugin extends JavaPlugin {
    /** The example pages copied into a new pages folder. */
    private static final String[] EXAMPLES = {"menu.html", "rules.html", "shop.html", "style.css"};

    private volatile PageStore pages;
    private Screens screens;
    private ExecutorService renderers;
    private int renderThreads;
    private Executor serverThread;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private BukkitTask watcher;
    private FileConfiguration cfg;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cfg = getConfig();
        Executor main = UIFramework.get().serverThread();
        serverThread = task -> main.execute(() -> {
            if (isEnabled()) task.run();
        });
        renderThreads = renderThreadsSetting();
        AtomicInteger n = new AtomicInteger();
        renderers = Executors.newFixedThreadPool(renderThreads, r -> {
            Thread t = new Thread(r, "UIEngine-render-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        pages = newPageStore();
        screens = new Screens(this, renderers);
        getServer().getPluginManager().registerEvents(screens, this);
        // Warm the renderer (fonts, the default stylesheet) so the first page a player opens is quick.
        renderers.execute(() -> ai.resourcepack.ui.html.text.Fonts.bundled());
        screens.load();
        UiCommand command = new UiCommand(this);
        getCommand("ui").setExecutor(command);
        getCommand("ui").setTabCompleter(command);
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> screens.tick(Bukkit.getCurrentTick()), 1, 1));
        watch();
        UIEngine.instance = this;
    }

    @Override
    public void onDisable() {
        UIEngine.instance = null;
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        if (watcher != null) watcher.cancel();
        if (screens != null) screens.shutdown();
        if (pages != null) pages.close();
        if (renderers != null) {
            renderers.shutdownNow();
            try {
                renderers.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        HandlerList.unregisterAll(this);
    }

    private int renderThreadsSetting() {
        return Math.max(1, cfg.getInt("render-threads", Math.max(1, Runtime.getRuntime().availableProcessors() / 2)));
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
     * tried again. Returns what only a restart applies, or null.
     */
    String reload() {
        reloadConfig();
        cfg = getConfig();
        PageStore old = pages;
        pages = newPageStore();
        old.close();
        Placeholders.reset();
        watch();
        for (String name : pages.names()) screens.reloaded(name);
        return renderThreadsSetting() != renderThreads ? "render-threads changes on the next restart." : null;
    }

    private void copyExample(String name, Path folder) throws IOException {
        try (InputStream in = getResource("pages/" + name)) {
            if (in != null) Files.copy(in, folder.resolve(name));
        }
    }

    PageStore pages() {
        return pages;
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

    boolean clickSound() {
        return cfg.getBoolean("click-sound", true);
    }

    double scrollStep() {
        return cfg.getDouble("scroll-step", 80);
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
