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
 */
public record ViewOptions(int pointerSize, boolean blur) {
    public static final ViewOptions DEFAULT = new ViewOptions(0, false);

    public ViewOptions {
        pointerSize = Math.max(0, pointerSize);
    }

    /** These options with [blur]. */
    public ViewOptions blur(boolean blur) {
        return new ViewOptions(pointerSize, blur);
    }
}
