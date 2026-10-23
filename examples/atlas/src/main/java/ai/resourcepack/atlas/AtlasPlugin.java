package ai.resourcepack.atlas;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.api.Click;
import ai.resourcepack.ui.framework.api.CloseReason;
import ai.resourcepack.ui.framework.api.Key;
import ai.resourcepack.ui.framework.api.Pointer;
import ai.resourcepack.ui.framework.api.UIFramework;
import ai.resourcepack.ui.framework.api.View;
import ai.resourcepack.ui.framework.api.ViewContent;
import ai.resourcepack.ui.framework.api.ViewOptions;
import ai.resourcepack.ui.framework.api.WallContent;
import ai.resourcepack.ui.framework.api.WallScreen;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Atlas: the game's own map, but live, as big as your screen, and something you can use. An example of a
 * plugin built on UI Engine's framework. {@code /atlas} opens it fullscreen; {@code /atlas wall} puts one on a wall.
 */
public final class AtlasPlugin extends JavaPlugin implements Listener {
    private static final TextColor GOLD = TextColor.color(0xFFAA00), GREY = TextColor.color(0xAAAAAA);
    private static final List<String> SUBCOMMANDS = List.of("open", "wall", "remove", "pins");
    private static final Set<BlockFace> WALL = Set.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);

    private UIFramework ui;
    private Terrain terrain;
    private Pins pins;
    private Font font;
    private final List<AtlasMap> maps = new CopyOnWriteArrayList<>();
    private final Map<String, Wall> walls = new LinkedHashMap<>();
    private volatile List<AtlasMap.Marker> players = List.of();
    private ScheduledExecutorService thread;

    private record Wall(String id, Location topLeft, BlockFace facing, int cols, int rows, AtlasMap map, WallScreen screen) {}

    @Override
    public void onEnable() {
        ui = UIFramework.get();
        terrain = new Terrain();
        pins = new Pins(new File(getDataFolder(), "pins.yml"));
        try (InputStream in = getResource("fonts/Monocraft.ttf")) {
            font = Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Could not load the bundled font; using a plain one", e);
            font = new Font(Font.MONOSPACED, Font.PLAIN, 16);
        }
        getServer().getPluginManager().registerEvents(this, this);
        // Every tick: where everybody is, and a few snapshots of the chunks the maps want.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            List<AtlasMap.Marker> now = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                Location l = p.getLocation();
                now.add(new AtlasMap.Marker(p.getUniqueId(), p.getName(), l.getWorld().getUID(), l.getX(), l.getZ(), l.getYaw()));
            }
            players = List.copyOf(now);
            terrain.capture(8);
        }, 1, 1);
        // Drawing happens here, ten frames a second, never on the server thread.
        thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Atlas-draw");
            t.setDaemon(true);
            return t;
        });
        long[] last = {System.nanoTime()};
        thread.scheduleAtFixedRate(() -> {
            long now = System.nanoTime();
            double dt = Math.min(0.2, (now - last[0]) / 1e9);
            last[0] = now;
            try {
                terrain.build();
                long ms = System.currentTimeMillis();
                for (AtlasMap m : maps) m.frame(dt, players, ms);
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Drawing a map failed", t);
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        Bukkit.getScheduler().runTask(this, this::loadWalls);
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            View v = ui.viewOf(p);
            if (v != null && v.content() instanceof MapView) v.close();
        }
        for (Wall w : walls.values()) w.screen().remove();
        if (thread != null) thread.shutdownNow();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlock();
        terrain.stale(b.getWorld().getUID(), b.getX() >> 4, b.getZ() >> 4);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        terrain.stale(b.getWorld().getUID(), b.getX() >> 4, b.getZ() >> 4);
    }

    // ---- The fullscreen map ----

    private final class MapView implements ViewContent {
        final AtlasMap map;

        MapView(AtlasMap map) {
            this.map = map;
        }

        @Override
        public Canvas canvas() {
            return map.canvas;
        }

        @Override
        public void pointer(View view, int x, int y) {
            map.px = x;
            map.py = y;
            view.pointer(map.pinAt(x, y) != null ? Pointer.HAND : Pointer.ARROW);
        }

        @Override
        public void click(View view, int x, int y, Click click) {
            if (!map.onMap(x, y)) return;
            Player p = view.player();
            if (click == Click.RIGHT) {
                if (p.hasPermission("atlas.teleport")) travel(view, map.blockX(x), map.blockZ(y));
                return;
            }
            Pins.Pin pin = map.pinAt(x, y);
            if (pin != null) {
                if (pin.owner().equals(p.getUniqueId())) {
                    pins.all.remove(pin);
                    savePins();
                    p.playSound(p.getLocation(), Sound.ENTITY_ITEM_FRAME_REMOVE_ITEM, 0.8f, 1f);
                }
                return;
            }
            if (!p.hasPermission("atlas.pin")) return;
            int bx = map.blockX(x), bz = map.blockZ(y);
            pins.add(new Pins.Pin(p.getUniqueId(), p.getName(), map.world, bx, bz));
            savePins();
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_FRAME_ADD_ITEM, 0.8f, 1f);
            say(p, "Pinned " + bx + ", " + bz + " for everybody.", GREY);
        }

        @Override
        public void scroll(View view, int x, int y, int notches) {
            map.zoom = Math.max(0, Math.min(AtlasMap.ZOOMS.length - 1, map.zoom - Integer.signum(notches)));
        }

        @Override
        public void key(View view, Key key, boolean down) {
            switch (key) {
                case UP -> map.north = down;
                case DOWN -> map.south = down;
                case LEFT -> map.west = down;
                case RIGHT -> map.east = down;
                case SWAP_HANDS, JUMP -> {
                    if (down) map.follow = true;
                }
                default -> {}
            }
        }

        @Override
        public void closed(View view, CloseReason reason) {
            maps.remove(map);
        }
    }

    private void open(Player p, UUID world, double x, double z, int zoom, boolean follow) {
        AtlasMap map = new AtlasMap(ui.canvas(16, 9), terrain, pins, font, p.getUniqueId(), p.getName(), world, x, z, zoom);
        map.follow = follow;
        maps.add(map);
        try {
            ui.openView(p, new MapView(map), ViewOptions.DEFAULT);
        } catch (IllegalStateException e) {
            maps.remove(map);
            say(p, e.getMessage(), NamedTextColor.RED);
        }
    }

    /** Right-click: get up from the map and go there, on top of whatever is there. */
    private void travel(View view, int x, int z) {
        Player p = view.player();
        World w = Bukkit.getWorld(((MapView) view.content()).map.world);
        if (w == null) return;
        view.close();
        Bukkit.getScheduler().runTask(this, () -> {
            int y = w.getHighestBlockYAt(x, z) + 1;
            Location to = new Location(w, x + 0.5, y, z + 0.5, p.getLocation().getYaw(), p.getLocation().getPitch());
            p.teleport(to);
            p.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f);
        });
    }

    private void savePins() {
        try {
            pins.save();
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not save pins.yml", e);
        }
    }

    // ---- Map walls ----

    private Wall putUp(String id, Location topLeft, BlockFace facing, int cols, int rows, double cx, double cz, int zoom) {
        AtlasMap map = new AtlasMap(ui.canvas(cols, rows), terrain, pins, font, null, null, topLeft.getWorld().getUID(), cx, cz, zoom);
        WallScreen screen = ui.placeWall(topLeft, facing, cols, rows, new WallContent() {
            @Override
            public Canvas canvas(Player viewer) {
                return map.canvas;
            }

            @Override
            public void click(Player viewer, int x, int y, Click click) {
                if (!viewer.hasPermission("atlas.open") || !map.onMap(x, y)) return;
                // Open the map fullscreen, at the place clicked.
                open(viewer, map.world, map.blockX(x) + 0.5, map.blockZ(y) + 0.5, 3, false);
            }

            @Override
            public boolean scroll(Player viewer, int x, int y, int notches) {
                map.zoom = Math.max(0, Math.min(AtlasMap.ZOOMS.length - 1, map.zoom - Integer.signum(notches)));
                return true;
            }
        });
        Wall w = new Wall(id, topLeft, facing, cols, rows, map, screen);
        walls.put(id, w);
        maps.add(map);
        return w;
    }

    private File wallsFile() {
        return new File(getDataFolder(), "walls.yml");
    }

    private void loadWalls() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(wallsFile());
        for (String id : yaml.getKeys(false)) {
            ConfigurationSection s = yaml.getConfigurationSection(id);
            World w = s == null ? null : Bukkit.getWorld(s.getString("world", ""));
            if (w == null) continue;
            try {
                putUp(id, new Location(w, s.getInt("x"), s.getInt("y"), s.getInt("z")), BlockFace.valueOf(s.getString("facing", "NORTH")),
                        s.getInt("columns", 8), s.getInt("rows", 5), s.getDouble("centre-x"), s.getDouble("centre-z"), s.getInt("zoom", 2));
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "Could not put map wall " + id + " back up", e);
            }
        }
    }

    private void saveWalls() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Wall w : walls.values()) {
            ConfigurationSection s = yaml.createSection(w.id());
            s.set("world", w.topLeft().getWorld().getName());
            s.set("x", w.topLeft().getBlockX());
            s.set("y", w.topLeft().getBlockY());
            s.set("z", w.topLeft().getBlockZ());
            s.set("facing", w.facing().name());
            s.set("columns", w.cols());
            s.set("rows", w.rows());
            s.set("centre-x", w.map().cx);
            s.set("centre-z", w.map().cz);
            s.set("zoom", w.map().zoom);
        }
        try {
            yaml.save(wallsFile());
        } catch (IOException e) {
            getLogger().log(Level.WARNING, "Could not save walls.yml", e);
        }
    }

    private Wall wallAt(Location l) {
        for (Wall w : walls.values()) {
            if (l.getWorld() != w.topLeft().getWorld()) continue;
            int dx = l.getBlockX() - w.topLeft().getBlockX(), dz = l.getBlockZ() - w.topLeft().getBlockZ();
            int along = dx * w.facing().getModZ() - dz * w.facing().getModX();
            int depth = dx * w.facing().getModX() + dz * w.facing().getModZ();
            int down = w.topLeft().getBlockY() - l.getBlockY();
            if (depth == 0 && along >= 0 && along < w.cols() && down >= 0 && down < w.rows()) return w;
        }
        return null;
    }

    // ---- Commands ----

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "open" : args[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(sub)) {
            say(sender, "/atlas [open] | wall [cols] [rows] | remove | pins clear", GREY);
            return true;
        }
        if (!sender.hasPermission("atlas." + sub)) {
            say(sender, "You can't do that.", NamedTextColor.RED);
            return true;
        }
        if (sub.equals("pins")) {
            if (args.length > 1 && args[1].equalsIgnoreCase("clear")) {
                pins.all.clear();
                savePins();
                say(sender, "Every pin taken down.", NamedTextColor.GREEN);
            } else {
                say(sender, pins.all.size() + " pins. /atlas pins clear takes them all down.", GREY);
            }
            return true;
        }
        if (!(sender instanceof Player p)) {
            say(sender, "Only a player can do that.", NamedTextColor.RED);
            return true;
        }
        Location here = p.getLocation();
        switch (sub) {
            case "open" -> open(p, here.getWorld().getUID(), here.getX(), here.getZ(), 3, true);
            case "wall" -> {
                int cols, rows;
                try {
                    cols = args.length > 1 ? Integer.parseInt(args[1]) : 8;
                    rows = args.length > 2 ? Integer.parseInt(args[2]) : 5;
                } catch (NumberFormatException e) {
                    say(p, "/atlas wall [cols] [rows]", GREY);
                    return true;
                }
                if (cols < 2 || rows < 2 || cols > 32 || rows > 18) {
                    say(p, "A map wall is 2 to 32 maps wide and 2 to 18 tall.", NamedTextColor.RED);
                    return true;
                }
                Block block = p.getTargetBlockExact(16);
                BlockFace face = p.getTargetBlockFace(16);
                if (block == null || face == null || !WALL.contains(face)) {
                    say(p, "Look at a wall: the block you look at is the map's top-left corner.", NamedTextColor.RED);
                    return true;
                }
                putUp("wall-" + UUID.randomUUID().toString().substring(0, 8), block.getRelative(face).getLocation(), face, cols, rows,
                        here.getX(), here.getZ(), 2);
                saveWalls();
                say(p, "Map up, centred where you stand. Click it to open it; scroll it to zoom.", NamedTextColor.GREEN);
            }
            case "remove" -> {
                Block block = p.getTargetBlockExact(16);
                BlockFace face = p.getTargetBlockFace(16);
                Wall w = block == null || face == null ? null : wallAt(block.getRelative(face).getLocation());
                if (w == null && block != null) w = wallAt(block.getLocation());
                if (w == null) {
                    say(p, "Look at a map wall to take it down.", NamedTextColor.RED);
                    return true;
                }
                w.screen().remove();
                maps.remove(w.map());
                walls.remove(w.id());
                saveWalls();
                say(p, "Map wall taken down.", NamedTextColor.GREEN);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBCOMMANDS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT)) && sender.hasPermission("atlas." + s)) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("pins") && sender.hasPermission("atlas.pins")) {
            out.add("clear");
        }
        return out;
    }

    private static void say(CommandSender to, String text, TextColor colour) {
        to.sendMessage(Component.text("Atlas ", GOLD).append(Component.text(text, colour)));
    }
}
