package ai.resourcepack.screens.api;

/**
 * How a view sits in the player's window.
 *
 * @param fill        how much of the window's height the picture fills, 0.3 to 1; the world shows round
 *                    it. Players without the resource pack always get 1: their view is cut through the
 *                    world, and the cut would show round a smaller one.
 * @param pointerSize the pointer's height in canvas pixels; 0 picks one from the canvas height.
 */
public record ViewOptions(double fill, int pointerSize) {
    public static final ViewOptions DEFAULT = new ViewOptions(0.9, 0);

    public ViewOptions {
        fill = Math.max(0.3, Math.min(1.0, fill));
        pointerSize = Math.max(0, pointerSize);
    }
}
