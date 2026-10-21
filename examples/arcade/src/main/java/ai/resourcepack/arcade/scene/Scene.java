package ai.resourcepack.arcade.scene;

import java.awt.Graphics2D;

/**
 * Something for the arcade to run: a game, a toy, a thing that moves. It knows nothing of Minecraft or of
 * UI Framework: it draws frames with Java2D and is told what its players do, and the arcade puts the frames
 * on a cabinet's wall and in each seated player's fullscreen view.
 *
 * <p>Every method is called on one thread, the arcade's loop, so a scene needs no locking. Each frame the
 * arcade delivers the input that arrived since the last one, then calls {@link #tick}, then {@link #draw}.
 * Coordinates are canvas pixels, (0, 0) top left, {@code cols() * 128} by {@code rows() * 128}.
 *
 * <p>With nobody seated a scene should still look alive (an attract mode, a demo playing itself), because a
 * cabinet on a wall is drawn for everybody walking past.
 */
public interface Scene {
    /** Width in maps (128 pixels each). 16 x 9 fills a 16:9 window exactly. */
    default int cols() {
        return 16;
    }

    default int rows() {
        return 9;
    }

    /** Frames a second, 1 to 30. Only the parts of a frame that changed are sent, so a still scene costs nothing. */
    default int fps() {
        return 30;
    }

    /** How many players can sit at it at once. */
    default int maxPlayers() {
        return 1;
    }

    /** Whether seated players see the pointer (an arrow). False when the scene draws its own (a paddle, a crosshair). */
    default boolean showPointer() {
        return true;
    }

    /** Called once, before anything else. */
    void start(Host host);

    /** Advance by [dt] seconds (usually 1 / fps). */
    void tick(double dt);

    /** Draw the whole frame. [g] is a fresh context on the frame, antialiasing on, every pixel to be painted. */
    void draw(Graphics2D g);

    default void joined(Seat seat) {}

    default void left(Seat seat) {}

    /** The seat's pointer moved. Players with the resource pack move it smoothly with their mouse. */
    default void pointer(Seat seat, int x, int y) {}

    /** A click at (x, y): left is the attack button, right the use button. */
    default void click(Seat seat, int x, int y, boolean right) {}

    /** The wheel turned, positive is down. */
    default void scroll(Seat seat, int notches) {}

    /** A key went down or up. Sneaking always gets the player up, so it is never a key. */
    default void key(Seat seat, Key key, boolean down) {}
}
