package ai.resourcepack.ui.framework.api;

/**
 * How a view looks. It always fills the player's window: with the resource pack the shader stretches
 * it to the window whatever its shape and the player's FOV; without, it is fitted to the FOV they gave
 * with {@code /ui fov}, for a 16:9 window.
 *
 * @param pointerSize the pointer's height in canvas pixels; 0 picks one from the canvas height.
 * @param blur        blur the world the player sees through the canvas's transparent pixels (see
 *                    {@link Canvas#transparency}), round the panel of everything opaque in it, with the game's own
 *                    menu blur at the player's own menu blur setting. Needs the resource pack (26.2), and makes the
 *                    server place the pointer, whose camera then stays still: the game blurs only a camera that is
 *                    another entity (a creeper), which the mouse cannot turn.
 * @param still       keep the world the player sees through the canvas's transparent pixels still: a camera that
 *                    is another entity, which the mouse cannot turn, and the server places the pointer (up to a tick
 *                    behind the mouse). Without it, a client-placed pointer turns the player's own camera, so the
 *                    world round the panel swings as the pointer moves. For any view with transparent pixels; a
 *                    blurred view is always still.
 */
public record ViewOptions(int pointerSize, boolean blur, boolean still) {
    public static final ViewOptions DEFAULT = new ViewOptions(0, false, false);

    public ViewOptions {
        pointerSize = Math.max(0, pointerSize);
    }

    /** These options with [blur]. */
    public ViewOptions blur(boolean blur) {
        return new ViewOptions(pointerSize, blur, still);
    }

    /** These options with [still]. */
    public ViewOptions still(boolean still) {
        return new ViewOptions(pointerSize, blur, still);
    }
}
