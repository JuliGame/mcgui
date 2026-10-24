package ai.resourcepack.ui.framework.world;

import ai.resourcepack.ui.framework.api.Click;
import ai.resourcepack.ui.framework.api.WallContent;
import ai.resourcepack.ui.framework.api.WallScreen;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Placed walls, and what players do to them: the pixel each player's crosshair is on (so a page can
 * show hover states), clicks, and the scroll wheel. Placed walls keep their chunks loaded, because
 * their frames are not saved with the world.
 */
public final class Walls implements Listener {
    private final Plugin plugin;
    private final double viewDistance;
    private final double reach;
    private final Predicate<Player> seated;
    private final List<Wall> walls = new CopyOnWriteArrayList<>();
    /** The same, as an array: what every tick goes through for every player, with no iterator made each time. */
    private volatile Wall[] placed = new Wall[0];
    /** The tick every wall's {@link Wall#viewers} are from (see {@link #viewers}). */
    private int viewersTick = -1;
    private int nextMapId;
    /** Every placed wall: each player's client gets one map for each distinct tile in them (see {@link MapPool}). */
    private final MapPool pool;

    /** A player's crosshair on a wall. */
    private record Look(Wall wall, int x, int y) {}

    private final Map<UUID, Look> looks = new HashMap<>();
    /** Who is in range of each wall, so a player walking off is told to the wall's content. */
    private final Map<Wall, Set<UUID>> inRange = new HashMap<>();
    private final Map<UUID, Long> lastSwing = new HashMap<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public Walls(Plugin plugin, int firstMapId, double viewDistance, double reach, Predicate<Player> seated) {
        this.plugin = plugin;
        this.nextMapId = firstMapId;
        this.pool = new MapPool(firstMapId);
        this.viewDistance = viewDistance;
        this.reach = reach;
        this.seated = seated;
    }

    public final class Wall implements WallScreen {
        final MapWall map;
        final WallContent content;
        volatile boolean removed;
        /**
         * Players in range as of [viewersTick], and those {@code inRange} was last worked out from. Kept and
         * refilled, so a tick in which nobody comes or goes allocates nothing. Server thread.
         */
        private final List<Player> viewers = new ArrayList<>(), counted = new ArrayList<>();
        private int viewersTick = -1;

        Wall(MapWall map, WallContent content) {
            this.map = map;
            this.content = content;
        }

        public MapWall map() { return map; }
        @Override public int cols() { return map.cols; }
        @Override public int rows() { return map.rows; }
        @Override public BlockFace facing() { return map.facing; }
        @Override public Location topLeft() { return new Location(map.world, map.anchorX, map.anchorY, map.anchorZ); }
        @Override public WallContent content() { return content; }
        @Override public boolean removed() { return removed; }

        @Override
        public void remove() {
            if (removed) return;
            removed = true;
            walls.remove(this);
            placed = walls.toArray(new Wall[0]);
            map.despawn();
            pool.remove(map);
            pin(map, false);
            Set<UUID> watchers = inRange.remove(this);
            if (watchers != null) for (UUID id : watchers) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) left(this, p);
            }
            looks.values().removeIf(l -> l.wall == this);
        }
    }

    public WallScreen place(Location topLeft, BlockFace facing, int cols, int rows, WallContent content) {
        MapWall map = new MapWall(topLeft.getWorld(), topLeft.getBlockX(), topLeft.getBlockY(), topLeft.getBlockZ(), facing, cols, rows, nextMapId);
        nextMapId += cols * rows;
        Wall wall = new Wall(map, content);
        pin(map, true);
        map.spawn(plugin);
        pool.add(map);
        walls.add(wall);
        placed = walls.toArray(new Wall[0]);
        return wall;
    }

    public List<Wall> all() {
        return walls;
    }

    public Wall byFrame(Entity entity) {
        for (Wall w : placed) if (w.map.isFrame(entity)) return w;
        return null;
    }

    /**
     * Players in range of [wall], seated players included (their own view may be beside it), as of this tick:
     * worked out for every wall at once, the first time any is asked about in a tick (see {@link #collectViewers}).
     * The list is the wall's own and refilled next tick: read it, never keep it.
     */
    public List<Player> viewers(Wall wall) {
        int tick = Bukkit.getCurrentTick();
        if (viewersTick != tick) collectViewers();
        // A wall placed since: its own.
        if (wall.viewersTick != tick) {
            wall.viewersTick = tick;
            wall.viewers.clear();
            double max = viewDistance * viewDistance;
            for (Player p : Bukkit.getOnlinePlayers()) if (within(p, p.getWorld(), wall.map, max)) wall.viewers.add(p);
        }
        return wall.viewers;
    }

    /**
     * Every wall's viewers in one go, each player's position read once: this runs every tick for every wall in
     * reach of every player, and the screens' tick and the sender both want the answer.
     */
    private void collectViewers() {
        int tick = Bukkit.getCurrentTick();
        viewersTick = tick;
        if (placed.length == 0) return;
        for (Wall wall : placed) {
            wall.viewersTick = tick;
            wall.viewers.clear();
        }
        double max = viewDistance * viewDistance;
        for (Player p : Bukkit.getOnlinePlayers()) {
            World world = p.getWorld();
            for (Wall wall : placed) if (within(p, world, wall.map, max)) wall.viewers.add(p);
        }
    }

    /** Whether [p], in [world], stands within the square root of [squared] blocks of [map]'s centre. Read straight off the entity: no Location made. */
    private static boolean within(Player p, World world, MapWall map, double squared) {
        return map.world == world && map.distanceSquared(p.getX(), p.getY(), p.getZ()) <= squared;
    }

    /** Whether [player] is near enough a wall to aim at it, from where they stand: no eye, ray or vector made. */
    private boolean nearAny(Player player) {
        World world = player.getWorld();
        for (Wall wall : placed) {
            // The eye is under 2 blocks above the feet.
            double r = reach + wall.map.radius() + 2;
            if (within(player, world, wall.map, r * r)) return true;
        }
        return false;
    }

    private static boolean same(List<Player> a, List<Player> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    /** Every tick: who is in range of what, and where each player's crosshair is. */
    public void tick() {
        collectViewers();
        for (Wall wall : placed) {
            // A frame can vanish (a /kill, another plugin); put it back.
            if (!wall.map.spawned() && wall.map.placeable()) wall.map.spawn(plugin);
            // Usually nobody came or went, and who is in range stands.
            List<Player> viewers = viewers(wall);
            if (same(viewers, wall.counted)) continue;
            wall.counted.clear();
            wall.counted.addAll(viewers);
            Set<UUID> now = new HashSet<>();
            for (Player p : viewers) now.add(p.getUniqueId());
            Set<UUID> before = inRange.put(wall, now);
            if (before != null) for (UUID id : before) {
                if (now.contains(id)) continue;
                Player p = Bukkit.getPlayer(id);
                if (p != null) left(wall, p);
            }
        }
        // No walls and nobody looking at one: nothing to aim at, and nothing to look away from.
        if (placed.length == 0 && looks.isEmpty()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Look now = seated.test(p) || !nearAny(p) ? null : aim(p);
            Look before = now == null ? looks.remove(p.getUniqueId()) : looks.put(p.getUniqueId(), now);
            // Each guarded: a content that throws must not cost every later wall and player their turn.
            try {
                if (before != null && (now == null || before.wall != now.wall)) before.wall.content.lookAway(p);
            } catch (RuntimeException | LinkageError e) {
                failed("on a look away", e);
            }
            try {
                if (now != null && (before == null || before.wall != now.wall || before.x != now.x || before.y != now.y)) {
                    now.wall.content.look(p, now.x, now.y);
                }
            } catch (RuntimeException | LinkageError e) {
                failed("on a look", e);
            }
        }
    }

    /** Tells [wall]'s content that [player] went out of range. Guarded: it is never asked again for them. */
    private void left(Wall wall, Player player) {
        try {
            wall.content.left(player);
        } catch (RuntimeException | LinkageError e) {
            failed("when a player left", e);
        }
    }

    /** A content callback threw: say so, and carry on with everything else (LinkageError: see Views.failed). */
    private void failed(String when, Throwable e) {
        plugin.getLogger().log(Level.WARNING, "A screen's content failed " + when, e);
    }

    /** The wall pixel [player]'s crosshair is on, if any wall is in reach and nothing solid is in the way. */
    private Look aim(Player player) {
        Location eye = player.getEyeLocation();
        Vector from = eye.toVector();
        Vector dir = eye.getDirection();
        Look best = null;
        double bestT = Double.MAX_VALUE;
        for (Wall wall : placed) {
            if (wall.map.world != player.getWorld()) continue;
            double[] hit = wall.map.pixelAlong(from, dir, reach);
            if (hit == null || hit[2] >= bestT) continue;
            best = new Look(wall, (int) hit[0], (int) hit[1]);
            bestT = hit[2];
        }
        if (best == null) return null;
        // A block between the eye and the picture hides it. The picture sits 1/16 off its wall, so stop just short.
        RayTraceResult block = player.getWorld().rayTraceBlocks(eye, dir, Math.max(0, bestT - 0.07), FluidCollisionMode.NEVER, true);
        return block == null ? best : null;
    }

    private Look lookOf(Player p) {
        return looks.get(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Look look = lookOf(e.getPlayer());
        if (look == null || seated.test(e.getPlayer())) return;
        // Attacking a frame and the swing arrive together: one click.
        long now = Bukkit.getCurrentTick();
        Long last = lastSwing.put(e.getPlayer().getUniqueId(), now);
        if (last != null && now - last < 2) return;
        look.wall.content.click(e.getPlayer(), look.x, look.y, Click.LEFT);
    }

    /** Left-clicking a block behind or beside the screen while looking at it must not break it. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (lookOf(e.getPlayer()) == null || seated.test(e.getPlayer())) return;
        switch (e.getAction()) {
            case LEFT_CLICK_BLOCK -> e.setCancelled(true);
            case RIGHT_CLICK_BLOCK, RIGHT_CLICK_AIR -> {
                e.setCancelled(true);
                if (e.getHand() != EquipmentSlot.HAND) return;
                rightClick(e.getPlayer(), lookOf(e.getPlayer()));
            }
            default -> {}
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onFrameClick(PlayerInteractEntityEvent e) {
        Wall wall = byFrame(e.getRightClicked());
        if (wall == null) return;
        e.setCancelled(true); // never rotate or empty a screen's frame
        if (e.getHand() != EquipmentSlot.HAND || seated.test(e.getPlayer())) return;
        Look look = lookOf(e.getPlayer());
        if (look != null && look.wall == wall) rightClick(e.getPlayer(), look);
    }

    /** Holding the button repeats the click every 4 ticks, and one press can arrive as two events: a fresh press only. */
    private void rightClick(Player player, Look look) {
        long now = Bukkit.getCurrentTick();
        Long last = lastUse.put(player.getUniqueId(), now);
        if (last != null && now - last <= 5) return;
        look.wall.content.click(player, look.x, look.y, Click.RIGHT);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onScroll(PlayerItemHeldEvent e) {
        Look look = lookOf(e.getPlayer());
        if (look == null || seated.test(e.getPlayer())) return;
        int delta = Math.floorMod(e.getNewSlot() - e.getPreviousSlot() + 4, 9) - 4;
        // The wheel moves one slot at a time; a bigger jump is a number key, which should not scroll.
        if (Math.abs(delta) != 1) return;
        if (look.wall.content.scroll(e.getPlayer(), look.x, look.y, delta)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrameDamage(EntityDamageEvent e) {
        if (byFrame(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFrameBreak(HangingBreakEvent e) {
        if (byFrame(e.getEntity()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        looks.remove(id);
        lastSwing.remove(id);
        lastUse.remove(id);
        // A copy: a content may remove its own wall from left(), which takes it out of inRange.
        for (Map.Entry<Wall, Set<UUID>> entry : new ArrayList<>(inRange.entrySet())) {
            if (entry.getValue().remove(id)) left(entry.getKey(), e.getPlayer());
        }
    }

    /** A wall in a world that goes away goes with it: it would keep the dead world loaded and never come back. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent e) {
        for (Wall w : walls) if (w.map.world == e.getWorld()) w.remove();
    }

    public void removeAll() {
        for (Wall w : new ArrayList<>(walls)) w.remove();
    }

    private void pin(MapWall map, boolean pin) {
        Set<Long> chunks = new HashSet<>();
        for (int[] b : map.blocks()) chunks.add(((long) (b[0] >> 4) << 32) | ((b[2] >> 4) & 0xFFFFFFFFL));
        for (long k : chunks) {
            int cx = (int) (k >> 32), cz = (int) k;
            if (pin) map.world.addPluginChunkTicket(cx, cz, plugin);
            else map.world.removePluginChunkTicket(cx, cz, plugin);
        }
    }
}
