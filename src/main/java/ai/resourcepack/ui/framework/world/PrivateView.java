package ai.resourcepack.ui.framework.world;

import io.papermc.paper.math.Position;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A view's wall exists for one player only and does not move anything: its frames are shown to them
 * alone, wherever it needs to be to fill their view, even inside the ground. With the resource pack
 * their client draws it in front of every block (the HI_OWN marker), so the world round the picture
 * stays whole. Without it, [clearBlocks]: they alone are told that the blocks between their eyes and
 * the picture are air, a cone exactly the picture's outline as seen from the camera. Nothing changes
 * on the server.
 */
final class PrivateView {
    private static final BlockData AIR = Material.AIR.createBlockData();
    /** A block that only touches the pyramid along a face or edge hides nothing. */
    private static final double EPS = 1e-6;

    private final MapWall wall;
    private final Location camera;
    private final boolean clearBlocks;
    /** Everything this player has been told is air, so all of it is put back. */
    private final Set<Position> cleared = new HashSet<>();
    /** Entities in the way that this player stops seeing meanwhile: another screen's frames, a mob. */
    private final Set<Entity> unseen = new HashSet<>();
    private Plugin plugin;

    PrivateView(MapWall wall, Location camera, boolean clearBlocks) {
        this.wall = wall;
        this.camera = camera;
        this.clearBlocks = clearBlocks;
    }

    void open(Plugin plugin, Player player) {
        this.plugin = plugin;
        refresh(player);
    }

    /** Send the air again: the client forgets it whenever the server sends those blocks for any reason. */
    void refresh(Player player) {
        if (!player.isOnline() || player.getWorld() != wall.world) return;
        if (clearBlocks) {
            Set<Position> now = cone();
            cleared.addAll(now);
            if (!now.isEmpty()) {
                Map<Position, BlockData> air = new HashMap<>();
                for (Position p : now) air.put(p, AIR);
                player.sendMultiBlockChange(air);
            }
        }
        if (plugin == null) return;
        Pyramid p = pyramid();
        if (p == null) return;
        for (Entity e : wall.world.getNearbyEntities(p.bounds)) {
            // Not what the player already cannot see: hidden for another reason (a seated player, see
            // Views.open), it must stay hidden when this view lets go. What this view hid and something
            // has shown again since (that player standing up) is hidden again.
            if (e == player || !e.isVisibleByDefault() || !player.canSee(e)) continue;
            BoundingBox b = e.getBoundingBox();
            // Level with the picture counts too: another screen's frames on the same wall would fight with it.
            if (p.reaches(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ(), 0.5)) {
                unseen.add(e);
                player.hideEntity(plugin, e);
            }
        }
    }

    /** Stops [player] seeing [e] until the view closes, like what is in the way (see refresh). */
    void hide(Player player, Entity e) {
        if (plugin == null || e == player || !e.isVisibleByDefault() || !player.canSee(e)) return;
        unseen.add(e);
        player.hideEntity(plugin, e);
    }

    /** Puts back everything this view hid, except what [stillHidden] says must stay hidden for another reason. */
    void close(Plugin plugin, Player player, Predicate<Entity> stillHidden) {
        for (Entity e : unseen) if (e.isValid() && !stillHidden.test(e)) player.showEntity(plugin, e);
        unseen.clear();
        if (!player.isOnline() || player.getWorld() != wall.world || cleared.isEmpty()) return;
        World world = wall.world;
        Map<Position, BlockData> real = new HashMap<>();
        for (Position p : cleared) {
            if (world.isChunkLoaded(p.blockX() >> 4, p.blockZ() >> 4)) real.put(p, world.getBlockAt(p.blockX(), p.blockY(), p.blockZ()).getBlockData());
        }
        player.sendMultiBlockChange(real);
        cleared.clear();
    }

    /**
     * The picture's outline as seen from the camera: four planes through the camera plus the picture's
     * own plane. Something can only hide part of the picture if, for each plane, some corner of it is on
     * the inner side; each test is linear, so checking the extreme corner is exact for that plane.
     */
    private final class Pyramid {
        final double depth, a, b, av, bv;
        final BoundingBox bounds;
        final double dx = -wall.facing.getModX(), dz = -wall.facing.getModZ();
        final double rx = wall.rightX, rz = wall.rightZ;

        Pyramid(double depth, double a, double b, double av, double bv, BoundingBox bounds) {
            this.depth = depth;
            this.a = a;
            this.b = b;
            this.av = av;
            this.bv = bv;
            this.bounds = bounds;
        }

        /** Whether the box reaches into the pyramid, running [beyond] past the picture. */
        boolean reaches(double x0, double y0, double z0, double x1, double y1, double z1, double beyond) {
            double tMin = Double.MAX_VALUE, tMax = -Double.MAX_VALUE;
            double l1 = Double.MAX_VALUE, l2 = -Double.MAX_VALUE, m1 = Double.MAX_VALUE, m2 = -Double.MAX_VALUE;
            for (double x : new double[]{x0, x1}) for (double z : new double[]{z0, z1}) for (double y : new double[]{y0, y1}) {
                double t = (x - camera.getX()) * dx + (z - camera.getZ()) * dz;
                double u = (x - camera.getX()) * rx + (z - camera.getZ()) * rz;
                double v = y - camera.getY();
                tMin = Math.min(tMin, t);
                tMax = Math.max(tMax, t);
                l1 = Math.min(l1, u - (a + b) * t);   // right edge: inside when <= 0
                l2 = Math.max(l2, u - (a - b) * t);   // left edge: inside when >= 0
                m1 = Math.min(m1, v - (av + bv) * t);
                m2 = Math.max(m2, v - (av - bv) * t);
            }
            return !(tMax <= 0 || tMin >= depth + beyond || l1 > -EPS || l2 < EPS || m1 > -EPS || m2 < EPS);
        }
    }

    private Pyramid pyramid() {
        World world = wall.world;
        Location c = wall.centre();
        double dx = -wall.facing.getModX(), dz = -wall.facing.getModZ();
        double rx = wall.rightX, rz = wall.rightZ;
        double depth = (c.getX() - camera.getX()) * dx + (c.getZ() - camera.getZ()) * dz;
        if (depth <= 0) return null;
        double u0 = (c.getX() - camera.getX()) * rx + (c.getZ() - camera.getZ()) * rz;
        double v0 = c.getY() - camera.getY();
        double halfW = wall.cols / 2.0, halfH = wall.rows / 2.0;
        double[] xs = {camera.getX(), c.getX() + rx * halfW, c.getX() - rx * halfW};
        double[] zs = {camera.getZ(), c.getZ() + rz * halfW, c.getZ() - rz * halfW};
        BoundingBox bounds = new BoundingBox(
                Math.floor(min(xs) - 1), Math.max(world.getMinHeight(), Math.floor(Math.min(camera.getY(), c.getY() - halfH) - 1)), Math.floor(min(zs) - 1),
                Math.ceil(max(xs) + 1), Math.min(world.getMaxHeight() - 1.0, Math.ceil(Math.max(camera.getY(), c.getY() + halfH) + 1)), Math.ceil(max(zs) + 1));
        return new Pyramid(depth, u0 / depth, halfW / depth, v0 / depth, halfH / depth, bounds);
    }

    /** Solid blocks that could hide any part of the picture from the camera: those reaching into the pyramid. */
    private Set<Position> cone() {
        World world = wall.world;
        Pyramid p = pyramid();
        Set<Position> out = new HashSet<>();
        if (p == null) return out;
        BoundingBox b = p.bounds;
        for (int x = (int) b.getMinX(); x <= (int) b.getMaxX(); x++) {
            for (int z = (int) b.getMinZ(); z <= (int) b.getMaxZ(); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                // The whole column first: most are outside and need no per-block test.
                if (!p.reaches(x, b.getMinY(), z, x + 1.0, b.getMaxY() + 1, z + 1.0, 0)) continue;
                for (int y = (int) b.getMinY(); y <= (int) b.getMaxY(); y++) {
                    if (!p.reaches(x, y, z, x + 1.0, y + 1.0, z + 1.0, 0)) continue;
                    if (!world.getBlockAt(x, y, z).getType().isAir()) out.add(Position.block(x, y, z));
                }
            }
        }
        return out;
    }

    private static double min(double[] v) {
        double m = v[0];
        for (double d : v) m = Math.min(m, d);
        return m;
    }

    private static double max(double[] v) {
        double m = v[0];
        for (double d : v) m = Math.max(m, d);
        return m;
    }
}
