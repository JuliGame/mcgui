package ai.resourcepack.ui;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UI Engine: HTML pages from {@code plugins/UIEngine/pages/}, shown in game on wall screens and in
 * fullscreen views, through Screen Engine. No browser: the pages are laid out and painted by the
 * plugin's own renderer, so what works is static HTML and CSS (no JavaScript), with links,
 * commands and hover states made to work in game.
 */
public final class UIEnginePlugin extends JavaPlugin {
    /** The example pages copied into a new pages folder. */
    private static final String[] EXAMPLES = {"menu.html", "rules.html", "shop.html", "style.css"};

    private PageStore pages;
    private Screens screens;
    private ExecutorService renderers;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private FileConfiguration cfg;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cfg = getConfig();
        Path folder = getDataFolder().toPath().resolve(cfg.getString("pages-folder", "pages")).normalize();
        try {
            if (!Files.isDirectory(folder)) {
                Files.createDirectories(folder);
                for (String name : EXAMPLES) copyExample(name, folder);
            }
        } catch (IOException e) {
            getLogger().warning("Could not create the pages folder: " + e.getMessage());
        }
        int threads = Math.max(1, cfg.getInt("render-threads", Math.max(1, Runtime.getRuntime().availableProcessors() / 2)));
        AtomicInteger n = new AtomicInteger();
        renderers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "UIEngine-render-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        Page.Loader loader = new Page.Loader(cfg.getBoolean("allow-web", true), getDataFolder().toPath().resolve("cache"));
        pages = new PageStore(folder, loader, getLogger());
        screens = new Screens(this, renderers);
        pages.onReload = name -> Bukkit.getScheduler().runTask(this, () -> screens.reloaded(name));
        // Warm the renderer (fonts, the default stylesheet) so the first page a player opens is quick.
        renderers.execute(() -> ai.resourcepack.ui.html.text.Fonts.bundled());
        screens.load();
        UiCommand command = new UiCommand(this);
        getCommand("ui").setExecutor(command);
        getCommand("ui").setTabCompleter(command);
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, () -> screens.tick(Bukkit.getCurrentTick()), 1, 1));
        if (cfg.getBoolean("live-reload", true)) {
            tasks.add(Bukkit.getScheduler().runTaskTimerAsynchronously(this, pages::checkForChanges, 20, 20));
        }
        UIEngine.instance = this;
    }

    @Override
    public void onDisable() {
        UIEngine.instance = null;
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        if (screens != null) screens.shutdown();
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

    /** Reads config.yml and the pages again, keeping placed screens. */
    void reload() {
        reloadConfig();
        cfg = getConfig();
        pages.clear();
        Placeholders.reset();
        for (String name : pages.names()) screens.reloaded(name);
    }

    private void copyExample(String name, Path folder) throws IOException {
        try (InputStream in = getResource("pages/" + name)) {
            if (in != null) Files.copy(in, folder.resolve(name));
        }
    }

    PageStore pages() {
        return pages;
    }

    Screens screens() {
        return screens;
    }

    boolean debug() {
        return cfg.getBoolean("debug", false);
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

    double viewFill(Page p) {
        String v = p.meta("ui:fill");
        try {
            return v != null ? Double.parseDouble(v.trim()) : cfg.getDouble("views.fill", 0.9);
        } catch (NumberFormatException e) {
            return 0.9;
        }
    }
}
