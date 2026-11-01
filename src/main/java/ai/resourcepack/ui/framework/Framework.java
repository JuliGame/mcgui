package ai.resourcepack.ui.framework;

import ai.resourcepack.ui.framework.platform.Bedrock;
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
import ai.resourcepack.ui.framework.pack.MergeCheck;
import ai.resourcepack.ui.framework.pack.ResourcePack;
import ai.resourcepack.ui.framework.pack.SiblingShaders;
import ai.resourcepack.ui.framework.pack.Siblings;
import ai.resourcepack.ui.framework.render.Palette;
import ai.resourcepack.ui.framework.world.Views;
import ai.resourcepack.ui.framework.world.Walls;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
import java.util.UUID;
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
    /** Which sibling packs replace our shaders; null unless ours is sent by MC GUI. */
    private SiblingShaders siblingShaders;
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
            // So Dialog Engine does not send its pack over ours again each time ours is sent (see ResourcePack.raise).
            Siblings.register(plugin);
            // Once the pack host has it (or this server serves it after all), the players already on are sent it.
            pack.start("MCGUI/" + plugin.getDescription().getVersion(), () -> {
                try {
                    Threads.global(plugin, () -> {
                        for (Player p : Bukkit.getOnlinePlayers()) {
                            if (!built.has(p.getUniqueId()) && !Bedrock.is(p)) Threads.on(plugin, p, () -> built.send(p));
                        }
                    });
                } catch (IllegalStateException | IllegalArgumentException disabled) {
                    // MC GUI stopped meanwhile.
                }
            });
            // Which of a sibling's packs bury ours, read from the packs themselves (only while ours is sent by us: merged,
            // ours is never buried).
            if (pack.offers()) siblingShaders = new SiblingShaders(pack.bytes, plugin.getLogger());
            // Merged into another plugin's pack: whether the merge kept everything, for every version, said in the
            // console. Pack plugins zip after MC GUI starts (and on their own reload commands), so it looks again
            // whenever their pack changes on disk.
            if (pack.mergedElsewhere()) {
                // Only the pack plugins this server has: another's zip left on disk (Oraxen's after a move to Nexo) is nobody's.
                MergeCheck check = new MergeCheck(plugin.getDataFolder().toPath().toAbsolutePath().getParent(), pack.bytes, plugin.getLogger(),
                        name -> Bukkit.getPluginManager().getPlugin(name) != null);
                tasks.add(Threads.asyncTimer(plugin, check::run, 20 * 10, 20 * 30));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not build or serve the resource pack", e);
        }
        pipeline = new Pipeline(nms, plugin.getLogger(), settings.hideHud);
        wire = new ServerWire(nms, pipeline, settings.sharedCompression ? SharedCompression.create(ServerWire.packets(nms), plugin.getLogger()) : null);
        sender = new MapSender(wire, settings.bytesPerSecond, plugin.getLogger());
        OpenTrace.start(plugin.getLogger(), nms, wire, sender);
        views = new Views(plugin, settings, nms, pipeline, sender, palette, settings.mapIdBase);
        views.packLoading(p -> pack != null && pack.raising(p.getUniqueId()), this::packLost);
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
            watchPacks(p);
            pipeline.trackHud(p);
            if (!Bedrock.joined(p) && pack != null) pack.send(p);
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
        if (siblingShaders != null) step("stop reading sibling packs", siblingShaders::close);
        siblingShaders = null;
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

    /** Why a Bedrock player gets no view: said to them, with no apostrophes (MC GUI's chat reads them as quotes). */
    public static final String BEDROCK_REFUSAL = "Screens work in Minecraft Java Edition only: Bedrock Edition cannot load the resource pack they are drawn with.";
    /** Why a player who declined the pack gets no view. */
    private static final String PACK_DECLINED = "You have not loaded the resource pack that screens need.";
    /** Why a player whose game failed to load the pack gets no view. */
    private static final String PACK_FAILED = "Your game could not load the resource pack of this server, which screens need.";

    @Override
    public View openView(Player player, ViewContent content, ViewOptions options) {
        if (Threads.folia() && !Threads.owns(player)) throw new IllegalStateException("A view is opened from " + player.getName() + "'s own thread (on Folia, their region's)");
        // A view's picture hangs wherever it fills the player's view, often inside the ground, and only the pack's
        // shaders draw it in front of the world. Without the pack there is no view: never one cut out of the world.
        // Said to the player (whoever opens a view for somebody else checks hasPack first), with no apostrophes,
        // which MC GUI's chat reads as quotes.
        // Geyser tells the server a required pack loaded when it did not (a Bedrock game cannot load one), so a Bedrock
        // player is refused here whatever the pack says, before anything about them changes.
        if (Bedrock.is(player)) throw new IllegalStateException(BEDROCK_REFUSAL);
        if (!hasPack(player)) {
            if (pack != null && pack.offers()) {
                pack.send(player);
                throw new IllegalStateException("You have not loaded the resource pack that screens need. It has been offered to you again.");
            }
            if (pack != null && pack.mergedElsewhere()) {
                throw new IllegalStateException(pack.declined(player.getUniqueId()) ? PACK_DECLINED : PACK_FAILED);
            }
            throw new IllegalStateException("Screens need the MC GUI resource pack, which is turned off on this server (resource-pack.mode).");
        }
        // Our pack goes on top again if a sibling plugin's went over it; the view then waits for it (Views.packLoading).
        pack.raise(player);
        return views.open(player, content, options == null ? ViewOptions.DEFAULT : options);
    }

    /**
     * Why [player]'s view, which waited for ours sent again (ResourcePack.raise), cannot be drawn now that the wait is
     * over: their game declined it or failed to load it. Null if it can.
     */
    private String packLost(Player player) {
        if (hasPack(player)) return null;
        return pack != null && pack.declined(player.getUniqueId()) ? PACK_DECLINED : PACK_FAILED;
    }

    @Override
    public View viewOf(Player player) {
        return views.viewOf(player);
    }

    @Override
    public boolean hasPack(Player player) {
        return pack != null && !Bedrock.is(player) && pack.has(player.getUniqueId());
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

    /**
     * Every pack sent to the player is seen on its way out (SiblingShaders): first of all join handlers, before any
     * other plugin's can send one.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoinFirst(PlayerJoinEvent e) {
        watchPacks(e.getPlayer());
        // First of all join handlers: whatever another plugin sends a player after this is seen, to be hidden at a page.
        pipeline.trackHud(e.getPlayer());
    }

    private void watchPacks(Player player) {
        SiblingShaders seen = siblingShaders;
        if (seen == null) return;
        UUID id = player.getUniqueId();
        pipeline.packPushes(player, push -> seen.pushed(id, push.id(), push.url(), push.hash()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        sender.reset(e.getPlayer().getUniqueId());
        // A Bedrock player (Geyser) cannot load a Java pack: none is offered, and walls show them plain map colours.
        boolean bedrock = Bedrock.joined(e.getPlayer());
        if (pack != null && !bedrock) pack.send(e.getPlayer());
        readUses(e.getPlayer());
    }

    /** Clicks on a wall's frames, which only the clients have, read off [player]'s connection (see Pipeline.useEntities). */
    private void readUses(Player player) {
        pipeline.useEntities(player, id -> Threads.player(plugin, player, nms.serverThread()).execute(() -> walls.frameUsed(player, id)));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (pack != null) pack.left(e.getPlayer().getUniqueId());
        if (siblingShaders != null) siblingShaders.left(e.getPlayer().getUniqueId());
        Bedrock.left(e.getPlayer().getUniqueId());
        sender.reset(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPack(PlayerResourcePackStatusEvent e) {
        if (pack == null) return;
        UUID id = e.getPlayer().getUniqueId();
        boolean ours = ResourcePack.PACK_ID.equals(e.getID());
        // A game takes packs in the order they are sent, each above the last: a sibling's taken after ours (Dialog
        // Engine's) is over ours if it replaces any of our files, and ours sent again is over it (see
        // ResourcePack.markBuried and raise).
        if (e.getStatus() == PlayerResourcePackStatusEvent.Status.ACCEPTED) {
            if (ours) pack.markAccepted(id);
            else if (pack.offers() && Siblings.fromSibling(plugin, e.getID())) siblingAccepted(e.getPlayer(), e.getID());
        }
        // Merged into another plugin's pack, ours never arrives under its own id: that pack is ours too.
        if (!ours && !pack.mergedElsewhere()) return;
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> pack.markLoaded(e.getPlayer().getUniqueId());
            case DECLINED -> pack.markRefused(e.getPlayer().getUniqueId(), true);
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD -> {
                pack.markRefused(e.getPlayer().getUniqueId(), false);
                // Merged, the pack is another plugin's and its failure says nothing on the server otherwise: a merge
                // that broke pack.mcmeta or a shader ends here, for every player.
                if (pack.mergedElsewhere()) {
                    plugin.getLogger().warning(e.getPlayer().getName() + "'s game could not load a resource pack this server sent ("
                            + e.getStatus() + "), so screens will not open for them. With MC GUI merged into another plugin's pack, "
                            + "see what MC GUI said about that pack at startup; the reason the game gives is in the player's own latest.log.");
                }
            }
            // Merged elsewhere, a discarded pack is usually the other plugin's being sent again, not ours going.
            case DISCARDED -> {
                if (!pack.mergedElsewhere()) pack.forget(e.getPlayer().getUniqueId());
            }
            default -> {}
        }
        // Ours, sent again, ended (loaded or not): after the above, so a view that waited for it sees whether it has the pack.
        if (ours && e.getStatus() != PlayerResourcePackStatusEvent.Status.ACCEPTED && e.getStatus() != PlayerResourcePackStatusEvent.Status.DOWNLOADED
                && e.getStatus() != PlayerResourcePackStatusEvent.Status.DISCARDED) pack.raised(id);
        // Their maps change meaning: send everything again in the right encoding.
        sender.reset(e.getPlayer().getUniqueId());
    }

    /**
     * A sibling's pack went over ours for [player]: ours counts as buried until that pack turns out to replace none of
     * our files (SiblingShaders). A view open meanwhile is closed once it does replace them (or that cannot be told),
     * as it would draw nothing of the page: this is a page whose button opened a Dialog Engine drawn dialog, which
     * then shows. Sending ours again instead would keep the page and break the dialog the player asked for.
     */
    private void siblingAccepted(Player player, UUID sibling) {
        UUID id = player.getUniqueId();
        SiblingShaders seen = siblingShaders;
        String by = seen == null ? "id:" + sibling : seen.key(id, sibling);
        pack.markBuried(id, by);
        if (seen == null) {
            buriedUnder(player, by, true);
            return;
        }
        java.util.concurrent.CompletableFuture<Boolean> replaces = seen.replaces(id, sibling);
        if (replaces.isDone()) {
            buriedUnder(player, by, replaces.join());
            return;
        }
        replaces.thenAccept(r -> {
            try {
                Threads.on(plugin, player, () -> buriedUnder(player, by, r));
            } catch (IllegalStateException | IllegalArgumentException gone) {
                // MC GUI stopped, or the player left, meanwhile.
            }
        });
    }

    /** Whether the sibling pack [by], over [player]'s ours, [replaces] any of our files (see siblingAccepted). */
    private void buriedUnder(Player player, String by, boolean replaces) {
        if (!replaces) {
            pack.harmless(by);
        } else if (player.isOnline() && pack.buriedBy(player.getUniqueId(), by) && views.viewOf(player) != null) {
            views.close(player, CloseReason.CLOSED);
        }
    }
}
