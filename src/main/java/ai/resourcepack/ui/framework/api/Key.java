package ai.resourcepack.ui.framework.api;

/**
 * The keys a seated player can press, as Minecraft reports them: movement, jump, sprint, the swap-hands key
 * (F by default), the drop key (Q) and the hotbar's number keys. Sneaking always closes the view, so it is
 * never a key. Movement, jump and sprint come down and up; the rest come as a press, down then up at once.
 *
 * <p>The scroll wheel and the number keys both move the hotbar, so the number keys of the slot the player
 * rests on and its two neighbours read as scrolling (or as nothing): see {@link View#restSlot(int)}.
 */
public enum Key {
    UP, DOWN, LEFT, RIGHT, JUMP, SPRINT, SWAP_HANDS, DROP,
    HOTBAR_1, HOTBAR_2, HOTBAR_3, HOTBAR_4, HOTBAR_5, HOTBAR_6, HOTBAR_7, HOTBAR_8, HOTBAR_9;

    /** The number key of hotbar slot [slot] (0 to 8). */
    public static Key hotbar(int slot) {
        return values()[HOTBAR_1.ordinal() + slot];
    }

    /** The hotbar slot (0 to 8) of a number key; -1 for any other key. */
    public int slot() {
        return ordinal() >= HOTBAR_1.ordinal() ? ordinal() - HOTBAR_1.ordinal() : -1;
    }
}
