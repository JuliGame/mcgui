package ai.resourcepack.ui.framework.world;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A view's wall exists for one player only and does not move anything: its frames are shown to them
 * alone, wherever it needs to be to fill their view, even inside the ground. Their client draws it in
 * front of every block (the resource pack's HI_OWN marker), so the world round the picture stays whole,
 * and no block is ever touched or hidden: a view needs the pack (see Framework#openView). Entities in
 * the way are hidden from them, and placed walls in the way take their frames off their client (see
 * {@link #hides}).
 */
final class PrivateView {
    /** A block that only touches the pyramid along a face or edge hides nothing. */
    private static final double EPS = 1e-6;

    private final MapWall wall;
    private final Location camera;
    /** Hide what is between the camera and the picture; not for an overlay, whose picture is no place in the world. */
    private final boolean hideInView;
    /** Entities in the way that this player stops seeing meanwhile: a mob, another player. */
    private final Set<Entity> unseen = new HashSet<>();
    /** Whether this view hides each placed wall asked about (see {@link #hides}): neither moves, so worked out once. */
    private final Map<MapWall, Boolean> hidesWall = new IdentityHashMap<>();
    private Plugin plugin;

    PrivateView(MapWall wall, Location camera, boolean hideInView) {
        this.wall = wall;
        this.camera = camera;
        this.hideInView = hideInView;
    }

    void open(Plugin plugin, Player player) {
        this.plugin = plugin;
        refresh(player);
    }

    /** Hides what has come into the way since: an entity that walked in. */
    void refresh(Player player) {
        if (!player.isOnline() || player.getWorld() != wall.world) return;
        if (plugin == null || !hideInView) return;
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

    /**
     * Whether placed wall [other] is in the way, like an entity {@link #refresh} hides: once the view is open, and
     * not for an overlay. Its frames are on clients only (see MapWall#share), so the wall takes them off this
     * player's client while this says so.
     */
    boolean hides(MapWall other) {
        if (plugin == null || !hideInView || other.world != wall.world) return false;
        Boolean known = hidesWall.get(other);
        if (known != null) return known;
        Pyramid p = pyramid();
        boolean hides = false;
        // Each frame hangs in its block, 1/16 deep against the wall behind it (the side away from [facing]).
        int fx = other.facing.getModX(), fz = other.facing.getModZ();
        double deep = 1.0 / 16;
        for (int[] b : other.blocks()) {
            if (p == null) break;
            double x0 = b[0], x1 = b[0] + 1, z0 = b[2], z1 = b[2] + 1;
            if (fx > 0) x1 = x0 + deep;
            else if (fx < 0) x0 = x1 - deep;
            if (fz > 0) z1 = z0 + deep;
            else if (fz < 0) z0 = z1 - deep;
            // Level with the picture counts too, as for an entity (see refresh).
            if (p.reaches(x0, b[1], z0, x1, b[1] + 1.0, z1, 0.5)) {
                hides = true;
                break;
            }
        }
        hidesWall.put(other, hides);
        return hides;
    }

    /** Puts back everything this view hid, except what [stillHidden] says must stay hidden for another reason. */
    void close(Plugin plugin, Player player, Predicate<Entity> stillHidden) {
        for (Entity e : unseen) if (e.isValid() && !stillHidden.test(e)) player.showEntity(plugin, e);
        unseen.clear();
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
