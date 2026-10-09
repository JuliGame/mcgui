package ai.resourcepack.ui;

import ai.resourcepack.screens.api.Canvas;
import ai.resourcepack.screens.api.Click;
import ai.resourcepack.screens.api.CloseReason;
import ai.resourcepack.screens.api.Key;
import ai.resourcepack.screens.api.Pointer;
import ai.resourcepack.screens.api.ScreenEngine;
import ai.resourcepack.screens.api.View;
import ai.resourcepack.screens.api.ViewContent;
import ai.resourcepack.screens.api.ViewOptions;
import ai.resourcepack.screens.api.WallContent;
import ai.resourcepack.screens.api.WallScreen;
import ai.resourcepack.ui.html.Page;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Pages on screens: placed wall screens (saved in screens.yml, each viewer browsing their own copy
 * unless the screen is shared) and fullscreen views a player sits at.
 */
public final class Screens {
    private final UIEnginePlugin plugin;
    private final ExecutorService renderers;
    private final File file;
    private final Map<String, Wall> walls = new ConcurrentHashMap<>();
    private final Map<UUID, Seated> views = new ConcurrentHashMap<>();

    Screens(UIEnginePlugin plugin, ExecutorService renderers) {
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
            Page p = plugin.pages().get(page);
            double designHeight = designHeight(p, height > 0 ? height : plugin.wallHeight(rows));
            Canvas canvas = ScreenEngine.get().canvas(cols, rows);
            Session s = new Session(plugin, renderers, canvas, id, designHeight);
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
            return session(viewer).canvas;
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
            session(viewer).click(x, y);
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

    public Wall place(String name, String page, Location topLeft, BlockFace facing, int cols, int rows, boolean shared, double height) {
        Wall old = walls.remove(name.toLowerCase());
        if (old != null) old.remove();
        Wall w = new Wall(name, page, topLeft, facing, cols, rows, shared, height);
        w.screen = ScreenEngine.get().placeWall(topLeft, facing, cols, rows, w);
        walls.put(name.toLowerCase(), w);
        save();
        return w;
    }

    public boolean remove(String name) {
        Wall w = walls.remove(name.toLowerCase());
        if (w == null) return false;
        w.remove();
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
                plugin.getLogger().warning("Screen '" + key + "' is in world '" + s.getString("world") + "', which is not loaded; skipping it");
                continue;
            }
            try {
                Wall w = new Wall(key, s.getString("page", "menu"), new Location(world, s.getInt("x"), s.getInt("y"), s.getInt("z")),
                        BlockFace.valueOf(s.getString("facing", "NORTH")), s.getInt("columns", 4), s.getInt("rows", 3),
                        s.getBoolean("shared", false), s.getDouble("height", 0));
                w.screen = ScreenEngine.get().placeWall(w.topLeft, w.facing, w.cols, w.rows, w);
                walls.put(key.toLowerCase(), w);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Could not put screen '" + key + "' back: " + e.getMessage());
            }
        }
        plugin.getLogger().info("Placed " + walls.size() + " screen(s)");
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

        @Override public Canvas canvas() { return session.canvas; }
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
            views.remove(session.viewer);
        }
    }

    /** Opens [page] fullscreen for [player]; false (with the reason told to them) if it cannot be. */
    public boolean open(Player player, String page) {
        Page p = plugin.pages().get(page);
        if (p == null) return false;
        int[] size = plugin.viewSize(p);
        Canvas canvas = ScreenEngine.get().canvas(size[0], size[1]);
        Session session = new Session(plugin, renderers, canvas, player.getUniqueId(), designHeight(p, plugin.viewHeight()));
        Seated seated = new Seated(session);
        session.pointerShape = shape -> {
            View v = seated.view;
            if (v != null && v.open() && v.pointer() != shape) v.pointer(shape);
        };
        session.close = () -> {
            View v = seated.view;
            if (v != null) v.close();
        };
        if (!session.open(page, null, false)) return false;
        Seated old = views.put(player.getUniqueId(), seated);
        if (old != null && old.view != null) old.view.close();
        try {
            seated.view = ScreenEngine.get().openView(player, seated, ViewOptions.DEFAULT);
        } catch (IllegalStateException e) {
            views.remove(player.getUniqueId());
            session.dispose();
            throw e;
        }
        seated.view.pointer(Pointer.ARROW);
        return true;
    }

    public boolean close(Player player) {
        Seated s = views.get(player.getUniqueId());
        if (s == null || s.view == null) return false;
        s.view.close();
        return true;
    }

    /** Every session on a page, for a reload. */
    void reloaded(String page) {
        for (Wall w : walls.values()) for (Session s : w.all()) s.reloaded(page);
        for (Seated s : views.values()) s.session.reloaded(page);
    }

    void tick(long now) {
        for (Wall w : walls.values()) for (Session s : w.all()) s.tick(now);
        for (Seated s : views.values()) s.session.tick(now);
    }

    void shutdown() {
        for (Seated s : new ArrayList<>(views.values())) if (s.view != null) s.view.close();
        views.clear();
        for (Wall w : walls.values()) w.remove();
        walls.clear();
    }
}
