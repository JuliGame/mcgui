package ai.resourcepack.uiframework.world;

import ai.resourcepack.uiframework.api.Click;
import ai.resourcepack.uiframework.api.WallContent;
import ai.resourcepack.uiframework.api.WallScreen;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
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
    private int nextMapId;

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
        this.viewDistance = viewDistance;
        this.reach = reach;
        this.seated = seated;
    }

    public final class Wall implements WallScreen {
        final MapWall map;
        final WallContent content;
        volatile boolean removed;

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
            map.despawn();
            pin(map, false);
            Set<UUID> watchers = inRange.remove(this);
            if (watchers != null) for (UUID id : watchers) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) content.left(p);
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
        walls.add(wall);
        return wall;
    }

    public List<Wall> all() {
        return walls;
    }

    public Wall byFrame(Entity entity) {
        for (Wall w : walls) if (w.map.isFrame(entity)) return w;
        return null;
    }

    /** Players in range of [wall], seated players included (their own view may be beside it). */
    public List<Player> viewers(Wall wall) {
        Location c = wall.map.centre();
        double max = viewDistance * viewDistance;
        List<Player> out = new ArrayList<>();
        for (Player p : wall.map.world.getPlayers()) if (p.getLocation().distanceSquared(c) <= max) out.add(p);
        return out;
    }

    /** Every tick: who is in range of what, and where each player's crosshair is. */
    public void tick() {
        for (Wall wall : walls) {
            // A frame can vanish (a /kill, another plugin); put it back.
            if (!wall.map.spawned() && wall.map.placeable()) wall.map.spawn(plugin);
            Set<UUID> now = new HashSet<>();
            for (Player p : viewers(wall)) now.add(p.getUniqueId());
            Set<UUID> before = inRange.put(wall, now);
            if (before != null) for (UUID id : before) {
                if (now.contains(id)) continue;
                Player p = Bukkit.getPlayer(id);
                if (p != null) wall.content.left(p);
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            Look now = seated.test(p) ? null : aim(p);
            Look before = now == null ? looks.remove(p.getUniqueId()) : looks.put(p.getUniqueId(), now);
            if (before != null && (now == null || before.wall != now.wall)) before.wall.content.lookAway(p);
            if (now != null && (before == null || before.wall != now.wall || before.x != now.x || before.y != now.y)) {
                now.wall.content.look(p, now.x, now.y);
            }
        }
    }

    /** The wall pixel [player]'s crosshair is on, if any wall is in reach and nothing solid is in the way. */
    private Look aim(Player player) {
        Location eye = player.getEyeLocation();
        Vector from = eye.toVector();
        Vector dir = eye.getDirection();
        Look best = null;
        double bestT = Double.MAX_VALUE;
        for (Wall wall : walls) {
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
        for (Map.Entry<Wall, Set<UUID>> entry : inRange.entrySet()) {
            if (entry.getValue().remove(id)) entry.getKey().content.left(e.getPlayer());
        }
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
