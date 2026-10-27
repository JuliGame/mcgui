package ai.resourcepack.ui.framework;

import ai.resourcepack.ui.framework.platform.Platform;
import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.api.CloseReason;
import ai.resourcepack.ui.framework.api.Cpu;
import ai.resourcepack.ui.framework.api.UIFramework;
import ai.resourcepack.ui.framework.api.View;
import ai.resourcepack.ui.framework.api.ViewContent;
import ai.resourcepack.ui.framework.api.ViewOptions;
import ai.resourcepack.ui.framework.api.WallContent;
import ai.resourcepack.ui.framework.api.WallScreen;
import ai.resourcepack.ui.framework.net.MapSender;
import ai.resourcepack.ui.framework.net.Nms;
import ai.resourcepack.ui.framework.net.Pipeline;
import ai.resourcepack.ui.framework.net.ServerWire;
import ai.resourcepack.ui.framework.net.SharedCompression;
import ai.resourcepack.ui.framework.pack.ResourcePack;
import ai.resourcepack.ui.framework.render.Palette;
import ai.resourcepack.ui.framework.world.Views;
import ai.resourcepack.ui.framework.world.Walls;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;
import ai.resourcepack.common.scheduler.Tasks;
import ai.resourcepack.ui.framework.platform.Threads;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * The framework under MC GUI's pages: full-colour pictures on walls of maps and in fullscreen views,
 * for vanilla clients. It owns the resource pack, the map ids and the seats, and is what
 * {@link UIFramework#get()} hands other plugins. Started and stopped by the plugin, before its pages
 * and after them.
 */
public final class Framework implements UIFramework, Listener {
    private final JavaPlugin plugin;
    private Settings settings;
    private Nms nms;
    private Palette palette;
    private ResourcePack pack;
    private MapSender sender;
    private ServerWire wire;
    private Pipeline pipeline;
    private Walls walls;
    private Views views;
    private final List<Tasks.Handle> tasks = new ArrayList<>();
    private Threads.PlayerTicks playerTicks;
    private java.util.concurrent.Executor serverThread;
    private final TickMeter meter = new TickMeter();
    /**
     * This tick's assignments, made into a list kept for it; and the last handed to the sender, never changed after.
     * Each wall's from last tick, in its viewers' order, reused while the same: a quiet tick makes none. Server thread.
     */
    private final ArrayList<MapSender.Assignment> assigning = new ArrayList<>();
    private ArrayList<MapSender.Assignment> assigned = new ArrayList<>();
    private final Map<Walls.Wall, ArrayList<MapSender.Assignment>> wallAssignments = new IdentityHashMap<>();

    public Framework(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Whether Minecraft version [version] ("1.21.10", "26.2") is older than [min]; false where it cannot be read. */
    static boolean olderThan(String version, int... min) {
        String[] parts = version.split("\\.");
        for (int i = 0; i < min.length; i++) {
            int v;
            try {
                v = i < parts.length ? Integer.parseInt(parts[i]) : 0;
            } catch (NumberFormatException e) {
                return false;
            }
            if (v != min[i]) return v < min[i];
        }
        return false;
    }

    /** Starts it, as the config says; false if this server is not one it can drive. */
    public boolean enable() {
        Threads.useFoliaTicks();
        settings = Settings.load(plugin.getConfig());
        Cpu.configure(settings.cpuThreads);
        String version = plugin.getServer().getBukkitVersion().split("-")[0];
        if (olderThan(version, 1, 21, 11)) {
            // 1.21.9 and 1.21.10 clients cannot tell they are seated (their shaders see no camera offset), and a
            // fullscreen view there is not something to ship.
            plugin.getLogger().severe("MC GUI needs Spigot or Paper 1.21.11 or newer; this server is " + version + ".");
            return false;
        }
        try {
            ai.resourcepack.ui.framework.net.Names.init(version);
            nms = new Nms(plugin.getServer());
        } catch (ReflectiveOperationException | LinkageError e) {
            plugin.getLogger().log(Level.SEVERE, "This server is not one MC GUI can drive (Spigot or Paper 1.21.11 or newer is needed): " + e, e);
            return false;
        }
        palette = new Palette(nms::mapColor);
        try {
            pack = new ResourcePack(settings, palette, plugin.getDataFolder().toPath(), plugin.getLogger());
            ResourcePack built = pack;
            // Once the pack host has it (or this server serves it after all), the players already on are sent it.
            pack.start("MCGUI/" + plugin.getDescription().getVersion(), () -> {
                try {
                    Threads.global(plugin, () -> {
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (!built.has(p.getUniqueId())) Threads.on(plugin, p, () -> built.send(p));
                        }
                    });
                } catch (IllegalStateException | IllegalArgumentException disabled) {
                    // MC GUI stopped meanwhile.
                }
            });
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not build or serve the resource pack", e);
        }
        pipeline = new Pipeline(nms, plugin.getLogger());
        wire = new ServerWire(nms, pipeline, settings.sharedCompression ? SharedCompression.create(ServerWire.packets(nms), plugin.getLogger()) : null);
        sender = new MapSender(wire, settings.bytesPerSecond, plugin.getLogger());
        OpenTrace.start(plugin.getLogger(), nms, wire, sender);
        views = new Views(plugin, settings, nms, pipeline, sender, palette, settings.mapIdBase);
        walls = new Walls(plugin, nms, settings.mapIdBase + Views.VIEW_ID_SPAN, settings.viewDistance, settings.reach, views::seated, views::hides);
        serverThread = Threads.server(plugin, nms.serverThread());
        Threads.register(this, plugin);
        Threads.register(walls, plugin);
        Threads.register(views, plugin);
        tasks.add(Threads.globalTimer(plugin, this::tick, 1, 1));
        // Folia: what each player's view and aim need, on their own thread (see Threads).
        playerTicks = Threads.PlayerTicks.start(plugin, this::playerTick);
        sender.start();
        // Players already online (a reload): hand them the pack again, and read their clicks on walls.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (pack != null) pack.send(p);
            readUses(p);
        }
        UIFramework.Holder.set(this);
        return true;
    }

    /** Stops it: closes every view, takes down every wall, stops serving the pack. */
    public void disable() {
        UIFramework.Holder.set(null);
        OpenTrace.stop();
        tasks.forEach(Tasks.Handle::cancel);
        tasks.clear();
        if (playerTicks != null) playerTicks.stop();
        playerTicks = null;
        if (views != null) step("close the open views", () -> views.closeAll(CloseReason.SHUTDOWN));
        if (walls != null) step("remove the walls", walls::removeAll);
        // After the views, whose closing still writes through them.
        if (pipeline != null) step("take the handlers out of players' connections", () -> pipeline.close(Bukkit.getOnlinePlayers()));
        if (sender != null) step("stop sending map pictures", sender::close);
        if (wire != null) step("let go of the map compressor", wire::close);
        if (pack != null) step("stop serving the resource pack", pack::close);
        Cpu.shutdown();
        HandlerList.unregisterAll(this);
        if (walls != null) HandlerList.unregisterAll(walls);
        if (views != null) HandlerList.unregisterAll(views);
    }

    /** One step of shutting down, on its own: one that fails must not skip the ones after it. */
    private void step(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().log(Level.SEVERE, "Could not " + what, e);
        }
    }

    private void tick() {
        if (Threads.folia()) Threads.countTick();
        meter.begin();
        try {
            work();
        } finally {
            meter.end(Platform.tick());
        }
    }

    /** Folia only: [player]'s share of the tick, on their thread. Elsewhere {@link #work} does it for everybody. */
    private void playerTick(Player player) {
        views.tick(player);
        walls.tick(player);
    }

    private void work() {
        views.tick();
        walls.tick();
        // Views first: the sender spends each player's budget in this order, and a seated player sees
        // nothing but their view, so it must not wait behind walls.
        ArrayList<MapSender.Assignment> out = assigning;
        out.clear();
        views.assignments(out);
        List<Walls.Wall> all = walls.all();
        for (int w = 0; w < all.size(); w++) {
            Walls.Wall wall = all.get(w);
            List<Player> viewers = walls.viewers(wall);
            ArrayList<MapSender.Assignment> last = wallAssignments.computeIfAbsent(wall, k -> new ArrayList<>());
            int kept = 0;
            for (int i = 0; i < viewers.size(); i++) {
                Player viewer = viewers.get(i);
                Canvas canvas;
                try {
                    canvas = wall.content().canvas(viewer);
                } catch (RuntimeException | LinkageError e) {
                    plugin.getLogger().log(Level.WARNING, "A screen's content failed", e);
                    continue;
                }
                if (canvas == null) continue;
                Canvas.Encoding encoding = hasPack(viewer) ? Canvas.Encoding.HI : Canvas.Encoding.PLAIN;
                MapSender.Assignment a = kept < last.size() ? last.get(kept) : null;
                if (a == null || a.player() != viewer || a.canvas() != canvas || a.encoding() != encoding) {
                    a = new MapSender.Assignment(viewer, wall.map(), canvas, encoding);
                    if (kept < last.size()) last.set(kept, a);
                    else last.add(a);
                }
                kept++;
                out.add(a);
                // The frames come once the picture has: before, they would show the map item.
                if (walls.wantsFrames(wall, viewer) && sender.delivered(a)) wall.map().showFrames(viewer);
            }
            while (last.size() > kept) last.removeLast();
        }
        if (wallAssignments.size() > all.size()) wallAssignments.keySet().removeIf(Walls.Wall::removed);
        // Usually the same as last tick, assignment for assignment: the sender has it, and a canvas that publishes
        // wakes it by itself.
        if (out.equals(assigned)) return;
        assigned = new ArrayList<>(out);
        sender.update(assigned);
    }

    // ---------------------------------------------------------------- API

    @Override
    public Canvas canvas(int cols, int rows) {
        return new Canvas(palette, cols, rows, sender::wake);
    }

    @Override
    public WallScreen placeWall(Location topLeft, BlockFace facing, int cols, int rows, WallContent content) {
        return walls.place(topLeft, facing, cols, rows, content);
    }

    @Override
    public View openView(Player player, ViewContent content, ViewOptions options) {
        if (Threads.folia() && !Threads.owns(player)) throw new IllegalStateException("A view is opened from " + player.getName() + "'s own thread (on Folia, their region's)");
        // A view's picture hangs wherever it fills the player's view, often inside the ground, and only the pack's
        // shaders draw it in front of the world. Without the pack there is no view: never one cut out of the world.
        // Said to the player (whoever opens a view for somebody else checks hasPack first), with no apostrophes,
        // which MC GUI's chat reads as quotes.
        if (!hasPack(player)) {
            if (pack != null && pack.offers()) {
                pack.send(player);
                throw new IllegalStateException("You have not loaded the resource pack that screens need. It has been offered to you again.");
            }
            if (pack != null && pack.mergedElsewhere()) throw new IllegalStateException("You have not loaded the resource pack that screens need.");
            throw new IllegalStateException("Screens need the MC GUI resource pack, which is turned off on this server (resource-pack.mode).");
        }
        return views.open(player, content, options == null ? ViewOptions.DEFAULT : options);
    }

    @Override
    public View viewOf(Player player) {
        return views.viewOf(player);
    }

    @Override
    public boolean hasPack(Player player) {
        return pack != null && pack.has(player.getUniqueId());
    }

    @Override
    public long bytesSent() {
        return sender.bytesSent();
    }

    @Override
    public java.util.concurrent.Executor serverThread() {
        return serverThread;
    }

    /** What the plugin's tick tasks cost the server thread (MC GUI's own tick adds to it too). */
    public TickMeter meter() {
        return meter;
    }

    Views views() {
        return views;
    }

    Walls walls() {
        return walls;
    }

    ResourcePack pack() {
        return pack;
    }

    // ---------------------------------------------------------------- the pack

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        sender.reset(e.getPlayer().getUniqueId());
        if (pack != null) pack.send(e.getPlayer());
        readUses(e.getPlayer());
    }

    /** Clicks on a wall's frames, which only the clients have, read off [player]'s connection (see Pipeline.useEntities). */
    private void readUses(Player player) {
        pipeline.useEntities(player, id -> Threads.player(plugin, player, nms.serverThread()).execute(() -> walls.frameUsed(player, id)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (pack != null) pack.forget(e.getPlayer().getUniqueId());
        sender.reset(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPack(PlayerResourcePackStatusEvent e) {
        // Merged into another plugin's pack, ours never arrives under its own id: that pack is ours too.
        if (pack == null || !ResourcePack.PACK_ID.equals(e.getID()) && !pack.mergedElsewhere()) return;
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> pack.markLoaded(e.getPlayer().getUniqueId());
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> pack.markRefused(e.getPlayer().getUniqueId());
            // Merged elsewhere, a discarded pack is usually the other plugin's being sent again, not ours going.
            case DISCARDED -> {
                if (!pack.mergedElsewhere()) pack.forget(e.getPlayer().getUniqueId());
            }
            default -> {}
        }
        // Their maps change meaning: send everything again in the right encoding.
        sender.reset(e.getPlayer().getUniqueId());
    }
}
