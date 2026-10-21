package ai.resourcepack.arcade;

import ai.resourcepack.uiframework.api.Canvas;
import ai.resourcepack.uiframework.api.Click;
import ai.resourcepack.uiframework.api.WallContent;
import ai.resourcepack.uiframework.api.WallScreen;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * An arcade cabinet: a wall of maps showing one game. While nobody plays it plays itself; click it and you
 * sit down at the same game fullscreen, and everybody standing round watches you play on the wall. The
 * wall and the view show the same {@link Canvas}, so each frame is drawn and encoded once for all of them.
 */
final class Cabinet implements WallContent {
    final String id;
    final Location topLeft;
    final BlockFace facing;
    final int cols, rows;
    final Game game;
    WallScreen screen;
    /** Who is sitting at it, or null. Server thread only. */
    UUID player;

    private final ArcadePlugin plugin;

    Cabinet(ArcadePlugin plugin, String id, Location topLeft, BlockFace facing, int cols, int rows, Game game) {
        this.plugin = plugin;
        this.id = id;
        this.topLeft = topLeft;
        this.facing = facing;
        this.cols = cols;
        this.rows = rows;
        this.game = game;
    }

    @Override
    public Canvas canvas(Player viewer) {
        return game.canvas;
    }

    @Override
    public void click(Player viewer, int x, int y, Click click) {
        plugin.sit(viewer, this);
    }

    /** The middle of the screen, a little in front of it: where its sounds come from. */
    Location centre() {
        int rx = facing.getModZ(), rz = -facing.getModX();
        return topLeft.clone().add(0.5 + rx * (cols / 2.0 - 0.5) + facing.getModX() * 0.5,
                1 - rows / 2.0,
                0.5 + rz * (cols / 2.0 - 0.5) + facing.getModZ() * 0.5);
    }

    /** Whether block [l] holds one of this cabinet's maps. */
    boolean covers(Location l) {
        if (l.getWorld() != topLeft.getWorld()) return false;
        int dx = l.getBlockX() - topLeft.getBlockX(), dz = l.getBlockZ() - topLeft.getBlockZ();
        int along = dx * facing.getModZ() - dz * facing.getModX();
        int depth = dx * facing.getModX() + dz * facing.getModZ();
        int down = topLeft.getBlockY() - l.getBlockY();
        return depth == 0 && along >= 0 && along < cols && down >= 0 && down < rows;
    }
}
