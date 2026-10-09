package ai.resourcepack.screens.api;

/**
 * What a fullscreen view shows, and what the seated player does with it. Every method runs on the
 * server thread. Pixel coordinates are canvas pixels from the top-left.
 */
public interface ViewContent {
    /** The view's picture. Asked every tick; the same canvas for the life of the view is usual. */
    Canvas canvas();

    /** The pointer moved to (x, y). */
    default void pointer(View view, int x, int y) {}

    default void click(View view, int x, int y, Click click) {}

    /** The wheel turned by [notches] (positive is down). */
    default void scroll(View view, int x, int y, int notches) {}

    default void key(View view, Key key, boolean down) {}

    /** The player typed [text] into chat while seated (it is not sent to chat). */
    default void chat(View view, String text) {}

    /** The view is gone; the player is back where they were. */
    default void closed(View view, CloseReason reason) {}
}
