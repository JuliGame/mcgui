package ai.resourcepack.uiframework;

import ai.resourcepack.uiframework.api.Canvas;
import ai.resourcepack.uiframework.api.CloseReason;
import ai.resourcepack.uiframework.api.UIFramework;
import ai.resourcepack.uiframework.api.View;
import ai.resourcepack.uiframework.api.ViewContent;
import ai.resourcepack.uiframework.api.ViewOptions;
import ai.resourcepack.uiframework.api.WallContent;
import ai.resourcepack.uiframework.api.WallScreen;
import ai.resourcepack.uiframework.net.MapSender;
import ai.resourcepack.uiframework.net.Nms;
import ai.resourcepack.uiframework.net.Pipeline;
import ai.resourcepack.uiframework.net.ServerWire;
import ai.resourcepack.uiframework.pack.ResourcePack;
import ai.resourcepack.uiframework.render.Palette;
import ai.resourcepack.uiframework.world.Views;
import ai.resourcepack.uiframework.world.Walls;
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
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class UIFrameworkPlugin extends JavaPlugin implements UIFramework, Listener {
    /** View walls share one range: each player only ever sees their own view's frames. */
    private static final int VIEW_ID_SPAN = 1024;

    private Settings settings;
    private Nms nms;
    private Palette palette;
    private ResourcePack pack;
    private MapSender sender;
    private Pipeline pipeline;
    private Walls walls;
    private Views views;
    private final List<BukkitTask> tasks = new ArrayList<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig());
        try {
            nms = new Nms(getServer());
        } catch (ReflectiveOperationException | LinkageError e) {
            getLogger().log(Level.SEVERE, "This server is not one UI Framework can drive (Paper 1.21.9 or newer is needed): " + e, e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        palette = new Palette(nms::mapColor);
        try {
            pack = new ResourcePack(settings, palette, getDataFolder().toPath(), getLogger());
            pack.start();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not build or serve the resource pack", e);
        }
        pipeline = new Pipeline(nms, getLogger());
        sender = new MapSender(new ServerWire(nms, pipeline), palette, settings.bytesPerSecond, getLogger());
        views = new Views(this, settings, nms, pipeline, id -> pack != null && pack.has(id), sender, settings.mapIdBase);
        walls = new Walls(this, settings.mapIdBase + VIEW_ID_SPAN, settings.viewDistance, settings.reach, views::seated);
        var pm = getServer().getPluginManager();
        pm.registerEvents(this, this);
        pm.registerEvents(walls, this);
        pm.registerEvents(views, this);
        tasks.add(Bukkit.getScheduler().runTaskTimer(this, this::tick, 1, 1));
        sender.start();
        var command = new UIFrameworkCommand(this);
        getCommand("uiframework").setExecutor(command);
        getCommand("uiframework").setTabCompleter(command);
        // Players already online (a reload): hand them the pack again.
        for (Player p : Bukkit.getOnlinePlayers()) if (pack != null) pack.send(p);
        UIFramework.Holder.set(this);
    }

    @Override
    public void onDisable() {
        UIFramework.Holder.set(null);
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        if (views != null) step("close the open views", () -> views.closeAll(CloseReason.SHUTDOWN));
        if (walls != null) step("remove the walls", walls::removeAll);
        // After the views, whose closing still writes through them.
        if (pipeline != null) step("take the handlers out of players' connections", () -> pipeline.close(Bukkit.getOnlinePlayers()));
        if (sender != null) step("stop sending map pictures", sender::close);
        if (pack != null) step("stop serving the resource pack", pack::close);
        HandlerList.unregisterAll((org.bukkit.plugin.Plugin) this);
    }

    /** One step of shutting down, on its own: one that fails must not skip the ones after it. */
    private void step(String what, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException | LinkageError e) {
            getLogger().log(Level.SEVERE, "Could not " + what, e);
        }
    }

    private void tick() {
        views.tick();
        walls.tick();
        // Views first: the sender spends each player's budget in this order, and a seated player sees
        // nothing but their view, so it must not wait behind walls.
        List<MapSender.Assignment> out = new ArrayList<>();
        views.assignments(out);
        for (Walls.Wall wall : walls.all()) {
            for (Player viewer : walls.viewers(wall)) {
                Canvas canvas;
                try {
                    canvas = wall.content().canvas(viewer);
                } catch (RuntimeException | LinkageError e) {
                    getLogger().log(Level.WARNING, "A screen's content failed", e);
                    continue;
                }
                if (canvas == null) continue;
                out.add(new MapSender.Assignment(viewer, wall.map(), canvas, hasPack(viewer) ? Canvas.Encoding.HI : Canvas.Encoding.PLAIN, null));
            }
        }
        sender.update(out);
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
        return nms.serverThread();
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
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (pack != null) pack.forget(e.getPlayer().getUniqueId());
        sender.reset(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPack(PlayerResourcePackStatusEvent e) {
        if (pack == null || !ResourcePack.PACK_ID.equals(e.getID())) return;
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> pack.markLoaded(e.getPlayer().getUniqueId());
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> pack.forget(e.getPlayer().getUniqueId());
            default -> {}
        }
        // Their maps change meaning: send everything again in the right encoding.
        sender.reset(e.getPlayer().getUniqueId());
    }
}
