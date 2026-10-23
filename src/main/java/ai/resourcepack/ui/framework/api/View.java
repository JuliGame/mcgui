package ai.resourcepack.ui.framework.api;

import org.bukkit.entity.Player;

/**
 * A fullscreen view: the player sits, their camera locks onto a picture only they can see, and their
 * mouse moves a pointer across it. Made with {@link UIFramework#openView}.
 */
public interface View {
    Player player();

    ViewContent content();

    int pointerX();

    int pointerY();

    /** Changes what the pointer looks like (a hand over a link, say). */
    void pointer(Pointer shape);

    Pointer pointer();

    /**
     * Rests the player's hotbar on [slot] (0 to 8) while they sit; they hold the slot they had again when they
     * get up. The wheel moves the hotbar one slot at a time, so the number keys of the resting slot and its two
     * neighbours read as the wheel (or nothing): rest it away from the number keys you listen for.
     */
    void restSlot(int slot);

    /** Whether the picture is on the player's screen yet (the view waits until it has arrived). */
    boolean shown();

    boolean open();

    void close();
}
