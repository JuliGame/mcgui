package ai.resourcepack.ui.framework.world;

import ai.resourcepack.ui.framework.net.Nms;
import ai.resourcepack.ui.framework.pack.Shaders;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A wall of invisible glow item frames, each holding a map id the engine owns. (anchorX, anchorY,
 * anchorZ) is the block holding the TOP-LEFT frame as seen by somebody facing the wall; the pictures
 * face [facing]. Map ids the engine owns have no saved data on the server, so vanilla never sends
 * anything for them: every pixel the client sees comes from the engine's sender. A shared wall's frames
 * are real entities; a view's exist on its viewer's client only (see {@link #spawnFor}).
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
    /** The centre of the picture, on its surface (see {@link #centre}): asked for by every player's aim, every tick. */
    private final double centreX, centreY, centreZ;
    private final int[] ownMaps;
    /** A shared wall's frames: real entities, in the world for everybody. */
    private final List<GlowItemFrame> frames = new ArrayList<>();
    private final Set<UUID> frameIds = new HashSet<>();
    /**
     * A view's frames exist on its one viewer's client only (see {@link #spawnFor}): the server never holds an
     * entity for them. Null for a shared wall.
     */
    private Nms nms;
    private Player owner;
    private int[] ownerStack;
    private boolean shownToOwner;
    /** The frames' entity ids in tile order, empty while there are none; read by the sender and the netty threads. */
    private volatile int[] entityIds = new int[0];
    /** Tile of each frame's entity id. */
    private volatile Map<Integer, Integer> tileOf = Map.of();

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
        double lateral = rightX + rightZ > 0 ? cols / 2.0 : 1 - cols / 2.0;
        // Maps sit 1/16 block off the wall; the wall is on the side opposite [facing].
        double n = facing.getModX() + facing.getModZ() > 0 ? 1.0 / 16 : 1 - 1.0 / 16;
        centreX = facing.getModX() != 0 ? anchorX + n : anchorX + lateral;
        centreY = anchorY + 1 - rows / 2.0;
        centreZ = facing.getModX() != 0 ? anchorZ + lateral : anchorZ + n;
        ownMaps = new int[cols * rows];
        for (int t = 0; t < ownMaps.length; t++) ownMaps[t] = mapId(t);
    }

    public int tiles() { return cols * rows; }

    public int mapId(int tile) { return firstMapId + tile; }

    /** Each tile's own map id, in tile order: one array for the wall's life, which nobody may change. */
    public int[] ownMaps() { return ownMaps; }

    /** The entity id of the frame showing [tile], or -1 while the frames are not in the world. */
    public int entityId(int tile) {
        int[] ids = entityIds;
        return tile < ids.length ? ids[tile] : -1;
    }

    /** Which tile the frame with entity id [entityId] shows, or -1 if it is not one of this wall's. */
    public int tileOfEntity(int entityId) {
        Integer t = tileOf.get(entityId);
        return t == null ? -1 : t;
    }

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
        return new Location(world, centreX, centreY, centreZ);
    }

    /** The world point at surface pixel (px, py), 128 pixels to a block, from the top-left. */
    public Vector pointAt(double px, double py) {
        return new Vector(centreX + rightX * (px / 128 - cols / 2.0), centreY + rows / 2.0 - py / 128, centreZ + rightZ * (px / 128 - cols / 2.0));
    }

    /**
     * Where a ray from [eye] along [dir] meets the picture, as surface pixels {x, y}, or null if it
     * misses, comes from behind, or meets it further than [maxDistance] blocks away.
     */
    public double[] pixelAlong(Vector eye, Vector dir, double maxDistance) {
        double nx = facing.getModX(), nz = facing.getModZ();
        // The picture's plane: points p with (p - c) . n == 0, n pointing out of the picture.
        double denom = dir.getX() * nx + dir.getZ() * nz;
        if (denom >= -1e-6) return null; // parallel, or looking from behind
        double t = ((centreX - eye.getX()) * nx + (centreZ - eye.getZ()) * nz) / denom;
        if (t < 0 || t > maxDistance) return null;
        double hx = eye.getX() + dir.getX() * t, hy = eye.getY() + dir.getY() * t, hz = eye.getZ() + dir.getZ() * t;
        double lateral = (hx - centreX) * rightX + (hz - centreZ) * rightZ;
        double px = (lateral + cols / 2.0) * 128;
        double py = (centreY + rows / 2.0 - hy) * 128;
        if (px < 0 || py < 0 || px >= cols * 128 || py >= rows * 128) return null;
        return new double[]{px, py, t};
    }

    /**
     * Frames for [viewer] alone, which only their client ever holds: sent to them by {@link #showTo}, taken
     * off by {@link #despawn}. Real frames, even hidden from everybody else, cost the server every tick for
     * every player near them: the entity tracker checks each one against each nearby player, and Paper's
     * activation range gathers them round each player. With a wall of up to a few hundred frames per seated
     * player, that grew with the square of the players seated together: 50 seated side by side took the tick
     * from 4 ms to 116 ms. These cost nothing per tick.
     *
     * With [stack] (a block) every frame lies in that one block, on its floor, rather than laid out as the
     * wall: for a view whose maps the viewer's client places in the window by what each map carries (see
     * Canvas.Encoding.HI_TILED). A frame lying at the camera's own block is inside the client's view frustum
     * whichever way the camera looks, so it is never culled.
     */
    public void spawnFor(Nms nms, Player viewer, int[] stack) {
        despawn();
        this.nms = nms;
        owner = viewer;
        ownerStack = stack;
        int[] ids = new int[tiles()];
        Map<Integer, Integer> tiles = new HashMap<>();
        for (int t = 0; t < ids.length; t++) {
            ids[t] = nms.nextEntityId(world);
            tiles.put(ids[t], t);
        }
        tileOf = Map.copyOf(tiles);
        entityIds = ids;
    }

    /** Shared frames everybody sees, in the world. */
    public void spawn(Plugin plugin) {
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
                });
                frames.add(frame);
                frameIds.add(frame.getUniqueId());
            }
        }
        int[] ids = new int[frames.size()];
        Map<Integer, Integer> tiles = new HashMap<>();
        for (int t = 0; t < ids.length; t++) {
            ids[t] = frames.get(t).getEntityId();
            tiles.put(ids[t], t);
        }
        tileOf = Map.copyOf(tiles);
        entityIds = ids;
    }

    /**
     * Brings a view's frames ({@link #spawnFor}) to its viewer, in bundles, each frame showing the map its
     * tile owns (the viewer's pipeline points it elsewhere on the way when it shares another tile's map).
     */
    public void showTo(Player viewer) {
        if (nms == null || viewer != owner) throw new IllegalStateException("Only a view's own viewer is shown its frames");
        int[] ids = entityIds;
        BlockFace face = ownerStack == null ? facing : BlockFace.UP;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Object> out = new ArrayList<>();
        for (int t = 0; t < ids.length; t++) {
            int[] b = ownerStack == null ? tileBlock(t % cols, t / cols) : ownerStack;
            out.addAll(nms.frameAppears(ids[t], new UUID(random.nextLong(), random.nextLong()), b[0], b[1], b[2], face, mapId(t)));
            // A client takes at most 4096 packets in one bundle.
            if (out.size() >= BUNDLE_MAX || t == ids.length - 1) {
                nms.send(viewer, nms.bundle(out));
                out = new ArrayList<>();
            }
        }
        shownToOwner = true;
    }

    private static final int BUNDLE_MAX = 4000;

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
        if (shownToOwner && owner.isOnline()) nms.send(owner, nms.removeEntities(entityIds));
        nms = null;
        owner = null;
        ownerStack = null;
        shownToOwner = false;
        entityIds = new int[0];
        tileOf = Map.of();
    }

    /** A shared wall's frames are all still in the world. */
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
