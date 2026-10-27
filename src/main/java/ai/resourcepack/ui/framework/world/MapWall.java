package ai.resourcepack.ui.framework.world;

import ai.resourcepack.ui.framework.net.Nms;
import ai.resourcepack.ui.framework.pack.Shaders;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A wall of invisible glow item frames, each holding a map id the engine owns. (anchorX, anchorY,
 * anchorZ) is the block holding the TOP-LEFT frame as seen by somebody facing the wall; the pictures
 * face [facing]. Map ids the engine owns have no saved data on the server, so vanilla never sends
 * anything for them: every pixel the client sees comes from the engine's sender. The frames exist on
 * clients only, never as server entities: a placed wall's on the clients of the players near it (see
 * {@link #share}), a view's on its one viewer's (see {@link #spawnFor}).
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
    /**
     * The frames exist on clients only (see {@link #share} and {@link #spawnFor}): the server never holds an entity
     * for them. Null until one of those is called.
     */
    private Nms nms;
    /** A view's one viewer; null for a placed wall. */
    private Player owner;
    private Stack ownerStack;
    private boolean shownToOwner;
    /**
     * A placed wall's: the players whose clients have its frames now, as a list (going through it makes nothing) and
     * a set (every player in range asks, every tick). Server thread.
     */
    private final List<Player> shown = new ArrayList<>();
    private final Set<Player> shownSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** The frames' entity ids in tile order, empty while there are none; read by the sender and the netty threads. */
    private volatile int[] entityIds = new int[0];
    /** Tile of each frame's entity id. */
    private volatile Map<Integer, Integer> tileOf = Map.of();
    /**
     * An overlay's panel (see {@link #sendOnly}): the tiles that are sent at all, or null for every tile. Read by
     * the sender's thread; a new array whenever it grows.
     */
    private volatile boolean[] sent;
    /** Which tiles' frames the owner's client has been shown. */
    private boolean[] framed;
    /** An overlay's signature frame, its entity id (see {@link #showSignature}); -1 for none. */
    private int signatureFrame = -1;
    private volatile int sendsVersion;
    /** The walls whose maps this one's frames may show, and theirs this one's (see {@link MapPool}); null for none. */
    volatile MapPool pool;

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

    /** The pool this wall's frames share maps in, or null if they show its own maps alone (a view). */
    public MapPool pool() {
        return pool;
    }

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

    /** The centre of the picture, on its surface. */
    public Location centre() {
        return new Location(world, centreX, centreY, centreZ);
    }

    /** The square of the distance from (x, y, z) to the picture's centre: what every player's range and aim test, every tick. */
    public double distanceSquared(double x, double y, double z) {
        double dx = x - centreX, dy = y - centreY, dz = z - centreZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /** How far the picture's corners are from its centre, in blocks. */
    public double radius() {
        return Math.hypot(cols, rows) / 2;
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
     * Where a view's frames all lie: in the camera's block, on its floor ([face] UP) when the camera is in the block's
     * lower half and on its ceiling (DOWN) otherwise. The client culls an entity whose box, grown by half a block
     * ({@code EntityRenderer.shouldRender}), is outside the view frustum; a frame holding a map is a block square and a
     * 16th deep, so grown it reaches from half a block below its block's floor to 9/16 above it (or the same from the
     * ceiling down), and the camera is always inside it: inside the box, no turn of the camera can cull it. On the
     * floor whatever the camera's height, an overlay (the player's own eye, 0.65 up its block when standing) lost
     * every map, so its panel, tint and kept world, in a band of looking up round 36° (with a 70° FOV, the bottom
     * of the view just above the frames), and F5's view from the front lost it looking down as much.
     */
    public record Stack(int x, int y, int z, BlockFace face) {
        public static Stack at(org.bukkit.Location cam) {
            double y = Math.floor(cam.getY());
            return new Stack(cam.getBlockX(), (int) y, cam.getBlockZ(), cam.getY() - y < 0.5 ? BlockFace.UP : BlockFace.DOWN);
        }

        int[] block() {
            return new int[]{x, y, z};
        }
    }

    /**
     * Frames for [viewer] alone, which only their client ever holds: sent to them by {@link #showTo}, taken
     * off by {@link #despawn}. Real frames, even hidden from everybody else, cost the server every tick for
     * every player near them: the entity tracker checks each one against each nearby player, and Paper's
     * activation range gathers them round each player. With a wall of up to a few hundred frames per seated
     * player, that grew with the square of the players seated together: 50 seated side by side took the tick
     * from 4 ms to 116 ms. These cost nothing per tick.
     *
     * With [stack] every frame lies in the camera's own block ({@link Stack}) rather than laid out as the wall: for
     * a view whose maps the viewer's client places in the window by what each map carries (see
     * Canvas.Encoding.HI_TILED).
     */
    public void spawnFor(Nms nms, Player viewer, Stack stack) {
        despawn();
        this.nms = nms;
        owner = viewer;
        ownerStack = stack;
        newIds(nms);
    }

    private void newIds(Nms nms) {
        int[] ids = new int[tiles()];
        Map<Integer, Integer> tiles = new HashMap<>();
        for (int t = 0; t < ids.length; t++) {
            ids[t] = nms.nextEntityId(world);
            tiles.put(ids[t], t);
        }
        tileOf = Map.copyOf(tiles);
        entityIds = ids;
    }

    /**
     * A placed wall's frames, for the players near it: entity ids the server never uses for an entity of its own,
     * brought to a client by {@link #showFrames} once its picture has reached it, and taken off by
     * {@link #hideFrames}. As server entities they reached clients as far as Paper's tracking range (96 blocks by
     * default) while pictures only go to players within the screens' view-distance, so the frames further off showed
     * the filled map item; and the tracker checked each one against each player near it, every tick.
     */
    public void share(Nms nms) {
        despawn();
        this.nms = nms;
        newIds(nms);
    }

    /** Whether [player]'s client has this placed wall's frames. Server thread. */
    public boolean shownTo(Player player) {
        return shownSet.contains(player);
    }

    /** The players whose clients have this placed wall's frames: read it, never keep it. Server thread. */
    public List<Player> shown() {
        return shown;
    }

    /**
     * Brings this placed wall's frames ({@link #share}) to [player]'s client, each showing its tile's own map (their
     * connection points it at the map the sender has it show, on the way out: see {@code Pipeline.FrameMaps}). Its
     * maps must have reached them first, or a frame shows the map item.
     */
    public void showFrames(Player player) {
        if (nms == null || owner != null) throw new IllegalStateException("Only a placed wall shows its frames to the players near it");
        if (!shownSet.add(player)) return;
        shown.add(player);
        int[] ids = entityIds;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Object> out = new ArrayList<>();
        for (int t = 0; t < ids.length; t++) {
            int[] b = tileBlock(t % cols, t / cols);
            out.addAll(nms.frameAppears(ids[t], new UUID(random.nextLong(), random.nextLong()), b[0], b[1], b[2], facing, mapId(t)));
        }
        for (int i = 0; i < out.size(); i += BUNDLE_MAX) nms.send(player, nms.bundle(out.subList(i, Math.min(out.size(), i + BUNDLE_MAX))));
    }

    /** Takes this placed wall's frames off [player]'s client, if it has them. */
    public void hideFrames(Player player) {
        if (shownSet.remove(player) && shown.remove(player) && player.isOnline()) nms.send(player, nms.removeEntities(entityIds));
    }

    /** [player]'s client no longer has this placed wall's frames: it left, or changed world or respawned, which drops them. */
    public void forget(Player player) {
        if (shownSet.remove(player)) shown.remove(player);
    }

    /**
     * Brings a view's frames ({@link #spawnFor}) to its viewer, in bundles, each frame showing the map its
     * tile owns (the viewer's pipeline points it elsewhere on the way when it shares another tile's map). Only
     * the tiles {@link #sends} and that they have not been shown yet: called again once an overlay's panel has
     * grown and the new tiles have reached them. Whether any were shown.
     */
    public boolean showTo(Player viewer) {
        return showTo(viewer, List.of());
    }

    /** As {@link #showTo(Player)}, with [first] at the head of the first bundle: the client handles them with the frames, in one go. */
    public boolean showTo(Player viewer, List<Object> first) {
        if (nms == null || viewer != owner) throw new IllegalStateException("Only a view's own viewer is shown its frames");
        int[] ids = entityIds;
        if (framed == null || framed.length != ids.length) framed = new boolean[ids.length];
        BlockFace face = ownerStack == null ? facing : ownerStack.face();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Object> out = new ArrayList<>(first);
        boolean any = false;
        for (int t = 0; t < ids.length; t++) {
            if (framed[t] || !sends(t)) continue;
            framed[t] = true;
            any = true;
            int[] b = ownerStack == null ? tileBlock(t % cols, t / cols) : ownerStack.block();
            out.addAll(nms.frameAppears(ids[t], new UUID(random.nextLong(), random.nextLong()), b[0], b[1], b[2], face, mapId(t)));
            // A client takes at most 4096 packets in one bundle.
            if (out.size() >= BUNDLE_MAX) {
                nms.send(viewer, nms.bundle(out));
                out = new ArrayList<>();
            }
        }
        if (!out.isEmpty()) nms.send(viewer, nms.bundle(out));
        shownToOwner = true;
        return any;
    }

    /** Whether a frame of every tile {@link #sends} has been shown to the owner (see {@link #showTo}). */
    public boolean allShown() {
        int[] ids = entityIds;
        for (int t = 0; t < ids.length; t++) if (sends(t) && (framed == null || !framed[t])) return false;
        return true;
    }

    /**
     * Brings the owner's client the frames of exactly the tiles [tiles] says, in one bundle: the ones missing appear,
     * the rest go (a view's hover layers, see LayerSet: a frame on the client costs it a draw call every frame, even one
     * whose map shows nothing). Each map must have reached the client before its frame does, or the frame shows the
     * map item.
     */
    public void frameOnly(Player viewer, boolean[] tiles) {
        if (nms == null || viewer != owner) throw new IllegalStateException("Only a view's own viewer is shown its frames");
        int[] ids = entityIds;
        if (framed == null || framed.length != ids.length) framed = new boolean[ids.length];
        BlockFace face = ownerStack == null ? facing : ownerStack.face();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Object> out = new ArrayList<>();
        int[] gone = new int[ids.length];
        int g = 0;
        for (int t = 0; t < ids.length; t++) {
            boolean want = t < tiles.length && tiles[t];
            if (want == framed[t]) continue;
            framed[t] = want;
            if (!want) {
                gone[g++] = ids[t];
                continue;
            }
            int[] b = ownerStack == null ? tileBlock(t % cols, t / cols) : ownerStack.block();
            out.addAll(nms.frameAppears(ids[t], new UUID(random.nextLong(), random.nextLong()), b[0], b[1], b[2], face, mapId(t)));
        }
        if (g > 0) out.add(0, nms.removeEntities(java.util.Arrays.copyOf(gone, g)));
        if (out.isEmpty()) return;
        for (int i = 0; i < out.size(); i += BUNDLE_MAX) nms.send(viewer, nms.bundle(out.subList(i, Math.min(out.size(), i + BUNDLE_MAX))));
        shownToOwner = true;
    }

    /**
     * Sends only the tiles [tiles] says, and frames only those (an overlay's panel: nothing is drawn where the
     * rest would be). Never fewer than before: a tile once shown keeps its frame.
     */
    public void sendOnly(boolean[] tiles) {
        boolean[] was = sent;
        boolean[] now = tiles.clone();
        if (was != null) for (int t = 0; t < now.length; t++) now[t] |= was[t];
        if (was != null && java.util.Arrays.equals(was, now)) return;
        sent = now;
        sendsVersion++;
    }

    /** Bumped each time {@link #sendOnly} grows what is sent. */
    public int sendsVersion() {
        return sendsVersion;
    }

    /** Whether tile [t] is sent at all (see {@link #sendOnly}). */
    public boolean sends(int t) {
        boolean[] s = sent;
        return s == null || (t < s.length && s[t]);
    }

    /** The map an overlay's signature frame shows: one past the tiles', in the views' own range. */
    public int signatureMapId() {
        return mapId(tiles());
    }

    /** Brings an overlay's signature frame to the owner, with the view's own frames (see {@link #spawnFor}). */
    public void showSignature(Player viewer) {
        if (nms == null || viewer != owner || ownerStack == null) throw new IllegalStateException("Only an overlay's own viewer is shown its signature");
        if (signatureFrame < 0) signatureFrame = nms.nextEntityId(world);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        nms.send(viewer, nms.bundle(nms.frameAppears(signatureFrame, new UUID(random.nextLong(), random.nextLong()),
                ownerStack.x(), ownerStack.y(), ownerStack.z(), ownerStack.face(), signatureMapId())));
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
        if (!shown.isEmpty()) {
            Object gone = nms.removeEntities(entityIds);
            for (Player p : shown) if (p.isOnline()) nms.send(p, gone);
            shown.clear();
            shownSet.clear();
        }
        if (shownToOwner && owner.isOnline()) {
            int[] ids = entityIds;
            if (signatureFrame >= 0) {
                ids = java.util.Arrays.copyOf(ids, ids.length + 1);
                ids[ids.length - 1] = signatureFrame;
            }
            nms.send(owner, nms.removeEntities(ids));
        }
        nms = null;
        owner = null;
        ownerStack = null;
        shownToOwner = false;
        entityIds = new int[0];
        tileOf = Map.of();
        framed = null;
        signatureFrame = -1;
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
