package ai.resourcepack.screens.api;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;

/** A placed wall of maps. Made with {@link ScreenEngine#placeWall}; gone after {@link #remove()} or a restart. */
public interface WallScreen {
    int cols();

    int rows();

    BlockFace facing();

    /** The block holding the top-left map, as seen by somebody facing the screen. */
    Location topLeft();

    WallContent content();

    /** Takes the frames down. */
    void remove();

    boolean removed();
}
