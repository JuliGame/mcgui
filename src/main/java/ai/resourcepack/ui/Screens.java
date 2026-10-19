package ai.resourcepack.ui;

import ai.resourcepack.uiframework.api.Canvas;
import ai.resourcepack.uiframework.api.Click;
import ai.resourcepack.uiframework.api.CloseReason;
import ai.resourcepack.uiframework.api.Key;
import ai.resourcepack.uiframework.api.Pointer;
import ai.resourcepack.uiframework.api.UIFramework;
import ai.resourcepack.uiframework.api.View;
import ai.resourcepack.uiframework.api.ViewContent;
import ai.resourcepack.uiframework.api.ViewOptions;
import ai.resourcepack.uiframework.api.WallContent;
import ai.resourcepack.uiframework.api.WallScreen;
import ai.resourcepack.ui.html.Page;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Pages on screens: placed wall screens (saved in screens.yml, each viewer browsing their own copy
 * unless the screen is shared) and fullscreen views a player sits at.
 */
public final class Screens implements Listener {
    private final UIEnginePlugin plugin;
    private final Renders renderers;
    private final File file;
    private final Map<String, Wall> walls = new ConcurrentHashMap<>();
    /**
     * Screens in screens.yml whose world is not loaded (yet: a multiworld plugin may load it after this
     * plugin starts), by lower-case name. Placed when their world loads, and written back meanwhile.
     */
    private final Map<String, ConfigurationSection> waiting = new ConcurrentHashMap<>();
    private final Map<UUID, Seated> views = new ConcurrentHashMap<>();
    /** Each player's latest fullscreen open still waiting for its page to load: an older one that loads later is dropped. */
    private final Map<UUID, Object> opening = new ConcurrentHashMap<>();

    Screens(UIEnginePlugin plugin, Renders renderers) {
        this.plugin = plugin;
        this.renderers = renderers;
        this.file = new File(plugin.getDataFolder(), "screens.yml");
    }

    // ---------------------------------------------------------------- walls

    /** A placed screen: where it is, what page it starts on, and a session per viewer (or one for all). */
    final class Wall implements WallContent {
        final String name;
        final String page;
        final Location topLeft;
        final BlockFace facing;
        final int cols, rows;
        final boolean shared;
        final double height;
        WallScreen screen;
        private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
        private Session sharedSession;

        Wall(String name, String page, Location topLeft, BlockFace facing, int cols, int rows, boolean shared, double height) {
            this.name = name;
            this.page = page;
            this.topLeft = topLeft;
            this.facing = facing;
            this.cols = cols;
            this.rows = rows;
            this.shared = shared;
            this.height = height;
        }

        private Session session(Player viewer) {
            if (shared) {
                if (sharedSession == null) {
                    sharedSession = newSession(null);
                }
                return sharedSession;
            }
            return sessions.computeIfAbsent(viewer.getUniqueId(), id -> newSession(id));
        }

        private Session newSession(UUID id) {
            // Called inside UI Framework's tick: the page loads elsewhere and shows when it has.
            Canvas canvas = UIFramework.get().canvas(cols, rows);
            Session s = new Session(plugin, renderers, canvas, id, height > 0 ? height : plugin.wallHeight(rows));
            s.close = () -> {
                if (s.pageName() != null && !s.pageName().equals(plugin.pages().normalise(page))) s.open(page, null, false);
            };
            s.open(page, null, false);
            return s;
        }

        Collection<Session> all() {
            List<Session> out = new ArrayList<>(sessions.values());
            if (sharedSession != null) out.add(sharedSession);
            return out;
        }

        @Override
        public Canvas canvas(Player viewer) {
            // Nothing until the page is on it (see Session.BLANK_AFTER_TICKS): then the first thing sent is the page.
            Session s = session(viewer);
            return s.showing() ? s.canvas : null;
        }

        @Override
        public void look(Player viewer, int x, int y) {
            session(viewer).pointer(x, y);
        }

        @Override
        public void lookAway(Player viewer) {
            Session s = shared ? sharedSession : sessions.get(viewer.getUniqueId());
            if (s != null) s.pointerGone();
        }

        @Override
        public void click(Player viewer, int x, int y, Click click) {
            Session s = session(viewer);
            if (click == Click.RIGHT && plugin.wallFullscreen()) {
                // Sit down at it: the page this screen is on for them, fullscreen.
                String at = s.pageName() != null ? s.pageName() : page;
                Consumer<String> tell = problem -> viewer.sendActionBar(Component.text(problem, NamedTextColor.RED));
                try {
                    open(viewer, at, tell);
                } catch (IllegalStateException e) {
                    tell.accept(e.getMessage());
                }
                return;
            }
            // On a shared screen the session has no player of its own: commands run as (and for) whoever clicked.
            s.click(x, y, viewer);
        }

        @Override
        public boolean scroll(Player viewer, int x, int y, int notches) {
            if (!plugin.wallScroll()) return false;
            session(viewer).scroll(notches);
            return true;
        }

        @Override
        public void left(Player viewer) {
            Session s = sessions.remove(viewer.getUniqueId());
            if (s != null) s.dispose();
        }

        void remove() {
            if (screen != null) screen.remove();
            for (Session s : all()) s.dispose();
            sessions.clear();
            sharedSession = null;
        }
    }

    /** A page's design height: its own {@code <meta name="ui:height">}, else [fallback]. */
    static double designHeight(Page p, double fallback) {
        if (p != null) {
            String v = p.meta("ui:height");
            if (v != null) {
                try {
                    return Math.max(64, Double.parseDouble(v.trim().replace("px", "")));
                } catch (NumberFormatException ignored) {
                    // the fallback
                }
            }
        }
        return fallback;
    }

    /** Places (or replaces) screen [name]. A '.' in the name is refused: screens.yml would read it back as a path. */
    public Wall place(String name, String page, Location topLeft, BlockFace facing, int cols, int rows, boolean shared, double height) {
        if (name.indexOf('.') >= 0) throw new IllegalArgumentException("a screen's name cannot have a '.' in it");
        Wall old = walls.remove(name.toLowerCase());
        if (old != null) old.remove();
        waiting.remove(name.toLowerCase());
        Wall w = new Wall(name, page, topLeft, facing, cols, rows, shared, height);
        w.screen = UIFramework.get().placeWall(topLeft, facing, cols, rows, w);
        walls.put(name.toLowerCase(), w);
        save();
        return w;
    }

    public boolean remove(String name) {
        Wall w = walls.remove(name.toLowerCase());
        boolean wasWaiting = waiting.remove(name.toLowerCase()) != null;
        if (w == null && !wasWaiting) return false;
        if (w != null) w.remove();
        save();
        return true;
    }

    public Collection<Wall> walls() {
        return walls.values();
    }

    /** The placed screen whose frame [location] is in, if any. */
    public Wall wallAt(Location block) {
        for (Wall w : walls.values()) {
            if (w.topLeft.getWorld() != block.getWorld()) continue;
            int rx = w.facing.getModZ(), rz = -w.facing.getModX();
            for (int r = 0; r < w.rows; r++) for (int c = 0; c < w.cols; c++) {
                int x = w.topLeft.getBlockX() + rx * c, y = w.topLeft.getBlockY() - r, z = w.topLeft.getBlockZ() + rz * c;
                if (x == block.getBlockX() && y == block.getBlockY() && z == block.getBlockZ()) return w;
            }
        }
        return null;
    }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("screens");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) continue;
            World world = Bukkit.getWorld(s.getString("world", ""));
            if (world == null) {
                plugin.getLogger().info("Screen '" + key + "' is in world '" + s.getString("world") + "', which is not loaded; it goes up when that world loads");
                waiting.put(key.toLowerCase(), s);
                continue;
            }
            putBack(s, world);
        }
        plugin.getLogger().info("Placed " + walls.size() + " screen(s)");
    }

    /** Places the screen saved as [s] in [world]; one that cannot be is kept as it was in screens.yml. */
    private void putBack(ConfigurationSection s, World world) {
        String key = s.getName();
        try {
            Wall w = new Wall(key, s.getString("page", "menu"), new Location(world, s.getInt("x"), s.getInt("y"), s.getInt("z")),
                    BlockFace.valueOf(s.getString("facing", "NORTH")), s.getInt("columns", 4), s.getInt("rows", 3),
                    s.getBoolean("shared", false), s.getDouble("height", 0));
            w.screen = UIFramework.get().placeWall(w.topLeft, w.facing, w.cols, w.rows, w);
            walls.put(key.toLowerCase(), w);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Could not put screen '" + key + "' back: " + e.getMessage());
            waiting.put(key.toLowerCase(), s);
        }
    }

    /** A world loaded after this plugin started (a multiworld plugin's): its screens go up. */
    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        World world = event.getWorld();
        for (Map.Entry<String, ConfigurationSection> e : waiting.entrySet()) {
            if (!world.getName().equals(e.getValue().getString("world"))) continue;
            waiting.remove(e.getKey());
            putBack(e.getValue(), world);
        }
    }

    void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Wall w : walls.values()) {
            String p = "screens." + w.name;
            yaml.set(p + ".page", w.page);
            yaml.set(p + ".world", w.topLeft.getWorld().getName());
            yaml.set(p + ".x", w.topLeft.getBlockX());
            yaml.set(p + ".y", w.topLeft.getBlockY());
            yaml.set(p + ".z", w.topLeft.getBlockZ());
            yaml.set(p + ".facing", w.facing.name());
            yaml.set(p + ".columns", w.cols);
            yaml.set(p + ".rows", w.rows);
            yaml.set(p + ".shared", w.shared);
            if (w.height > 0) yaml.set(p + ".height", w.height);
        }
        // Screens not up yet are written back as they were read, or they would be lost on the first save.
        for (ConfigurationSection s : waiting.values()) {
            for (String k : s.getKeys(true)) if (!s.isConfigurationSection(k)) yaml.set("screens." + s.getName() + "." + k, s.get(k));
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not save screens.yml: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- views

    /** A player seated at a page. */
    final class Seated implements ViewContent {
        final Session session;
        View view;

        Seated(Session session) {
            this.session = session;
        }

        /**
         * Nothing until the page is on it (see Session.BLANK_AFTER_TICKS), so the view is sent the page first, not
         * a blank: the view opens at once, and its frames go up while the page is drawn. Asked at the open too,
         * for the view's size, before [view] is set.
         */
        @Override
        public Canvas canvas() {
            return view == null || session.showing() ? session.canvas : null;
        }

        @Override public void pointer(View v, int x, int y) { session.pointer(x, y); }
        @Override public void click(View v, int x, int y, Click click) { session.click(x, y); }
        @Override public void scroll(View v, int x, int y, int notches) { session.scroll(notches); }

        @Override
        public void key(View v, Key key, boolean down) {
            if (!down) return;
            switch (key) {
                case UP -> session.scroll(-1);
                case DOWN -> session.scroll(1);
                case SWAP_HANDS -> {
                    if (!session.goBack()) v.close();
                }
                default -> {}
            }
        }

        @Override
        public void closed(View v, CloseReason reason) {
            session.dispose();
            // Only if it is still this view's: one opening in its place closes this one after taking the slot.
            views.remove(session.viewer, this);
        }
    }

    /**
     * Opens [page] fullscreen for [player]: at once if the page is loaded, else once it has loaded.
     * False if there is no such page. Seating them can fail (no room where they are): at once, that
     * throws IllegalStateException; later, [problem] hears it, as it hears a page that fails to load.
     */
    public boolean open(Player player, String page, Consumer<String> problem) {
        String n = plugin.pages().normalise(page);
        if (n == null || !plugin.pages().exists(n)) return false;
        UUID id = player.getUniqueId();
        Page p = plugin.pages().loaded(n);
        if (p != null) {
            opening.remove(id);
            return seat(player, n, p);
        }
        Object ticket = new Object();
        opening.put(id, ticket);
        plugin.pages().load(n).thenAcceptAsync(loaded -> {
            if (!opening.remove(id, ticket)) return;
            Player now = Bukkit.getPlayer(id);
            if (now == null) return;
            try {
                if (loaded == null || !seat(now, n, loaded)) problem.accept("Could not open '" + page + "': see the console.");
            } catch (IllegalStateException e) {
                problem.accept(e.getMessage());
            }
        }, plugin.serverThread());
        return true;
    }

    /** Seats [player] at page [p], named [n], fullscreen. */
    private boolean seat(Player player, String n, Page p) {
        int[] size = plugin.viewSize(p);
        Canvas canvas = UIFramework.get().canvas(size[0], size[1]);
        Session session = new Session(plugin, renderers, canvas, player.getUniqueId(), plugin.viewHeight());
        Seated seated = new Seated(session);
        session.pointerShape = shape -> {
            View v = seated.view;
            if (v != null && v.open() && v.pointer() != shape) v.pointer(shape);
        };
        session.close = () -> {
            View v = seated.view;
            if (v != null) v.close();
        };
        if (!session.open(n, null, false)) return false;
        Seated old = views.put(player.getUniqueId(), seated);
        if (old != null && old.view != null) old.view.close();
        try {
            seated.view = UIFramework.get().openView(player, seated, ViewOptions.DEFAULT);
        } catch (IllegalStateException e) {
            views.remove(player.getUniqueId(), seated);
            session.dispose();
            throw e;
        }
        seated.view.pointer(Pointer.ARROW);
        return true;
    }

    /** Closes [player]'s page, or the one still loading for them. */
    public boolean close(Player player) {
        boolean wasOpening = opening.remove(player.getUniqueId()) != null;
        Seated s = views.get(player.getUniqueId());
        if (s == null || s.view == null) return wasOpening;
        s.view.close();
        return true;
    }

    /** Every session on a page, for a reload. */
    void reloaded(String page) {
        plugin.firstPictures().invalidate(page, plugin.pages().loaded(page));
        for (Wall w : walls.values()) for (Session s : w.all()) s.reloaded(page);
        for (Seated s : views.values()) s.session.reloaded(page);
    }

    void tick(long now) {
        for (Wall w : walls.values()) for (Session s : w.all()) s.tick(now);
        for (Seated s : views.values()) s.session.tick(now);
    }

    void shutdown() {
        opening.clear();
        for (Seated s : new ArrayList<>(views.values())) if (s.view != null) s.view.close();
        views.clear();
        for (Wall w : walls.values()) w.remove();
        walls.clear();
    }
}
