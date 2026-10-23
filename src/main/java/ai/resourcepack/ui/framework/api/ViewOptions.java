package ai.resourcepack.ui.framework.api;

/**
 * How a view looks. It always fills the player's window: with the resource pack the shader stretches
 * it to the window whatever its shape and the player's FOV; without, it is fitted to the FOV they gave
 * with {@code /ui fov}, for a 16:9 window.
 *
 * @param pointerSize the pointer's height in canvas pixels; 0 picks one from the canvas height.
 */
public record ViewOptions(int pointerSize) {
    public static final ViewOptions DEFAULT = new ViewOptions(0);

    public ViewOptions {
        pointerSize = Math.max(0, pointerSize);
    }
}
