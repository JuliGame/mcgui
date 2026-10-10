package ai.resourcepack.uiframework.world;

import ai.resourcepack.uiframework.pack.Shaders;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A wall of invisible glow item frames, each holding a map id the engine owns. (anchorX, anchorY,
 * anchorZ) is the block holding the TOP-LEFT frame as seen by somebody facing the wall; the pictures
 * face [facing]. Map ids the engine owns have no saved data on the server, so vanilla never sends
 * anything for them: every pixel the client sees comes from the engine's sender.
 */
public final class MapWall {
    public static final Set<BlockFace> HORIZONTAL = Set.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);

    public final World world;
    public final int anchorX, anchorY, anchorZ;
    public final BlockFace facing;
    public final int cols, rows;
    public final int firstMapId;
    /** Right, as seen by somebody facing the wall. */
    public final int rightX, rightZ;
    private final List<GlowItemFrame> frames = new ArrayList<>();
    private final Set<UUID> frameIds = new HashSet<>();

    public MapWall(World world, int anchorX, int anchorY, int anchorZ, BlockFace facing, int cols, int rows, int firstMapId) {
        if (!HORIZONTAL.contains(facing)) throw new IllegalArgumentException("A screen faces north, south, east or west");
        this.world = world;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.facing = facing;
        this.cols = cols;
        this.rows = rows;
        this.firstMapId = firstMapId;
        this.rightX = facing.getModZ();
        this.rightZ = -facing.getModX();
    }

    public int tiles() { return cols * rows; }

    public int mapId(int tile) { return firstMapId + tile; }

    public int[] tileBlock(int c, int r) {
        return new int[]{anchorX + rightX * c, anchorY - r, anchorZ + rightZ * c};
    }

    public List<int[]> blocks() {
        List<int[]> out = new ArrayList<>();
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) out.add(tileBlock(c, r));
        return out;
    }

    public boolean isFrame(Entity entity) { return frameIds.contains(entity.getUniqueId()); }

    /** The centre of the picture, on its surface. */
    public Location centre() {
        double lateral = rightX + rightZ > 0 ? cols / 2.0 : 1 - cols / 2.0;
        double y = anchorY + 1 - rows / 2.0;
        // Maps sit 1/16 block off the wall; the wall is on the side opposite [facing].
        double n = facing.getModX() + facing.getModZ() > 0 ? 1.0 / 16 : 1 - 1.0 / 16;
        return facing.getModX() != 0 ? new Location(world, anchorX + n, y, anchorZ + lateral) : new Location(world, anchorX + lateral, y, anchorZ + n);
    }

    /** The world point at surface pixel (px, py), 128 pixels to a block, from the top-left. */
    public Vector pointAt(double px, double py) {
        Location c = centre();
        return new Vector(c.getX() + rightX * (px / 128 - cols / 2.0), c.getY() + rows / 2.0 - py / 128, c.getZ() + rightZ * (px / 128 - cols / 2.0));
    }

    /**
     * Where a ray from [eye] along [dir] meets the picture, as surface pixels {x, y}, or null if it
     * misses, comes from behind, or meets it further than [maxDistance] blocks away.
     */
    public double[] pixelAlong(Vector eye, Vector dir, double maxDistance) {
        Location c = centre();
        double nx = facing.getModX(), nz = facing.getModZ();
        // The picture's plane: points p with (p - c) . n == 0, n pointing out of the picture.
        double denom = dir.getX() * nx + dir.getZ() * nz;
        if (denom >= -1e-6) return null; // parallel, or looking from behind
        double t = ((c.getX() - eye.getX()) * nx + (c.getZ() - eye.getZ()) * nz) / denom;
        if (t < 0 || t > maxDistance) return null;
        double hx = eye.getX() + dir.getX() * t, hy = eye.getY() + dir.getY() * t, hz = eye.getZ() + dir.getZ() * t;
        double lateral = (hx - c.getX()) * rightX + (hz - c.getZ()) * rightZ;
        double px = (lateral + cols / 2.0) * 128;
        double py = (c.getY() + rows / 2.0 - hy) * 128;
        if (px < 0 || py < 0 || px >= cols * 128 || py >= rows * 128) return null;
        return new double[]{px, py, t};
    }

    /** [viewer]: frames only they will see, shown to them now or, without [show], later with {@link #showTo}. */
    public void spawn(Plugin plugin, Player viewer, boolean show) {
        despawn();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int[] b = tileBlock(c, r);
                ItemStack item = new ItemStack(Material.FILLED_MAP);
                MapMeta meta = (MapMeta) item.getItemMeta();
                meta.setMapId(mapId(r * cols + c));
                item.setItemMeta(meta);
                GlowItemFrame frame = world.spawn(new Location(world, b[0], b[1], b[2]), GlowItemFrame.class, f -> {
                    f.setFacingDirection(facing, true);
                    f.setItem(item, false);
                    f.setFixed(true);
                    f.setVisible(false);
                    f.setInvulnerable(true);
                    f.setPersistent(false);
                    f.setSilent(true);
                    if (viewer != null) f.setVisibleByDefault(false);
                });
                if (viewer != null && show) viewer.showEntity(plugin, frame);
                frames.add(frame);
                frameIds.add(frame.getUniqueId());
            }
        }
    }

    public void spawn(Plugin plugin) {
        spawn(plugin, null, true);
    }

    public void hideFrom(Plugin plugin, Player player) {
        for (GlowItemFrame f : frames) player.hideEntity(plugin, f);
    }

    public void showTo(Plugin plugin, Player player) {
        for (GlowItemFrame f : frames) player.showEntity(plugin, f);
    }

    /** Whether every frame's block is inside the world's height and in a loaded chunk. */
    public boolean placeable() {
        for (int[] b : blocks()) {
            if (b[1] < world.getMinHeight() || b[1] >= world.getMaxHeight() || !world.isChunkLoaded(b[0] >> 4, b[2] >> 4)) return false;
        }
        return true;
    }

    public void despawn() {
        for (GlowItemFrame f : frames) f.remove();
        frames.clear();
        frameIds.clear();
    }

    public boolean spawned() {
        if (frames.isEmpty()) return false;
        for (GlowItemFrame f : frames) if (!f.isValid()) return false;
        return true;
    }

    /**
     * Where a seated player's camera goes: centred, just far enough back that the picture fills a
     * [fov]° vertical field of view on a 16:9 window, with the coordinate along [facing] snapped to the
     * nearest flagged position (see {@link Shaders#FLAG_PHASE}). The pack's shaders read that fraction
     * back to know the player is seated; nothing else puts a camera there.
     */
    public Location cameraLocation(double fov) {
        Location c = centre();
        int sign = facing.getModX() + facing.getModZ();
        double along = facing.getModX() != 0 ? c.getX() : c.getZ();
        double v = snap(along + sign * viewDistance(cols, rows, fov));
        Location loc = facing.getModX() != 0 ? new Location(world, v, c.getY(), c.getZ()) : new Location(world, c.getX(), c.getY(), v);
        loc.setYaw(yawFacing(-facing.getModX(), -facing.getModZ()));
        loc.setPitch(0);
        return loc;
    }

    /** Snap to k/16 + 3/128: moves the camera at most 1/32 of a block, and a player's own eye is essentially never exactly there. */
    public static double snap(double v) {
        return Math.round((v - Shaders.FLAG_PHASE) / Shaders.FLAG_STEP) * Shaders.FLAG_STEP + Shaders.FLAG_PHASE;
    }

    /** Distance at which a cols × rows picture exactly fills a [fov]° vertical view on a 16:9 window. */
    public static double viewDistance(int cols, int rows, double fov) {
        double halfV = Math.toRadians(fov / 2);
        double halfH = Math.atan(Math.tan(halfV) * 16 / 9);
        // 1% spare covers the snap, so the picture never overflows the window.
        return Math.max(rows / 2.0 / Math.tan(halfV), cols / 2.0 / Math.tan(halfH)) * 1.01;
    }

    /** Minecraft yaw for looking along (dx, dz): 0 = south, 90 = west, 180 = north, -90 = east. */
    public static float yawFacing(int dx, int dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
