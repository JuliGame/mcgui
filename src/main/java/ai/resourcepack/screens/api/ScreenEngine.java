package ai.resourcepack.screens.api;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

/**
 * Screen Engine: pictures in vanilla Minecraft, drawn on walls of maps and in fullscreen views that a
 * player sits at and drives with their mouse. A plugin draws into a {@link Canvas}; the engine gets it
 * to the players looking at it, changed tiles only, in full colour for players with its resource pack.
 *
 * <pre>{@code
 * ScreenEngine engine = ScreenEngine.get();
 * Canvas canvas = engine.canvas(4, 3);           // 512 x 384 pixels
 * canvas.draw(myImage, 0, 0);
 * engine.placeWall(topLeft, BlockFace.SOUTH, 4, 3, viewer -> canvas);
 * }</pre>
 */
public interface ScreenEngine {
    /** The running engine. Depend on the {@code ScreenEngine} plugin to have it enabled first. */
    static ScreenEngine get() {
        ScreenEngine engine = Holder.engine;
        if (engine == null) throw new IllegalStateException("Screen Engine is not enabled");
        return engine;
    }

    /** A blank canvas [cols] × [rows] maps big (128 × 128 pixels each). */
    Canvas canvas(int cols, int rows);

    /**
     * Puts up a wall of [cols] × [rows] maps with its top-left map in block [topLeft], facing [facing]
     * (north, south, east or west). The frames are invisible and nothing can break them; they are not
     * saved with the world, so place it again after a restart.
     */
    WallScreen placeWall(Location topLeft, BlockFace facing, int cols, int rows, WallContent content);

    /**
     * Seats [player] at a fullscreen view of [content]'s canvas. Their view switches once the picture
     * has reached them; until then they see the world. Any view they already had closes first.
     *
     * @throws IllegalStateException if there is no room for the view where they are
     */
    View openView(Player player, ViewContent content, ViewOptions options);

    /** [player]'s open view, or null. */
    View viewOf(Player player);

    /** Whether [player] has the engine's resource pack loaded (full colour, smooth pointer). */
    boolean hasPack(Player player);

    /** Bytes of map data sent since the engine started. */
    long bytesSent();

    /** Set by the plugin itself; not for other plugins. */
    final class Holder {
        private Holder() {}

        static volatile ScreenEngine engine;

        public static void set(ScreenEngine e) {
            engine = e;
        }
    }
}
