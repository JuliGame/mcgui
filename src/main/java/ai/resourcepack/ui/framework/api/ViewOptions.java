package ai.resourcepack.ui.framework.api;

/**
 * How a view looks. It always fills the player's window: with the resource pack the shader stretches
 * it to the window whatever its shape and the player's FOV; without, it is fitted to the FOV they gave
 * with {@code /mcgui fov}, for a 16:9 window.
 *
 * @param pointerSize the pointer's height in canvas pixels; 0 picks one from the canvas height.
 * @param blur        an overlay only: blur the world round the panel, as the game blurs it behind a menu, at the
 *                    player's own menu blur setting.
 * @param overlay     the canvas is a panel over the world (see {@link Canvas#transparency}): with the resource pack
 *                    (26.2) only the maps its panel covers are sent and drawn, the player's view stays exactly where it
 *                    was, and the world round the panel is the picture of it their client took as the view opened,
 *                    kept still (and frozen) until it closes, under the canvas's tint, while the pointer follows the
 *                    mouse at frame rate. Without the pack, the canvas fills the view, the world showing where it is
 *                    transparent.
 */
public record ViewOptions(int pointerSize, boolean blur, boolean overlay) {
    public static final ViewOptions DEFAULT = new ViewOptions(0, false, false);

    public ViewOptions {
        pointerSize = Math.max(0, pointerSize);
    }

    /** These options with [blur]. */
    public ViewOptions blur(boolean blur) {
        return new ViewOptions(pointerSize, blur, overlay);
    }

    /** These options with [overlay]. */
    public ViewOptions overlay(boolean overlay) {
        return new ViewOptions(pointerSize, blur, overlay);
    }
}
