package ai.resourcepack.uiframework.api;

import org.bukkit.entity.Player;

/**
 * What a wall screen shows, and what happens when somebody looks at it and clicks. Every method runs
 * on the server thread. Pixel coordinates are canvas pixels from the top-left.
 */
public interface WallContent {
    /**
     * The canvas [viewer] sees: every viewer the same one, or each their own. Asked every tick for
     * every player in range, so it must be cheap. Null shows them nothing new.
     */
    Canvas canvas(Player viewer);

    /** [viewer]'s crosshair is over pixel (x, y). Called whenever that pixel changes. */
    default void look(Player viewer, int x, int y) {}

    /** [viewer]'s crosshair has left the screen (or they walked out of reach). */
    default void lookAway(Player viewer) {}

    /** [viewer] clicked at (x, y). */
    default void click(Player viewer, int x, int y, Click click) {}

    /**
     * [viewer] turned the scroll wheel by [notches] (positive is down) while looking at (x, y). Return
     * true to take it, which leaves their hotbar selection where it was.
     */
    default boolean scroll(Player viewer, int x, int y, int notches) {
        return false;
    }

    /** [viewer] is out of range: forget anything kept for them. */
    default void left(Player viewer) {}
}
