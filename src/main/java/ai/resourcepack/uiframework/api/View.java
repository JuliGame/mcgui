package ai.resourcepack.uiframework.api;

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

    /** Whether the picture is on the player's screen yet (the view waits until it has arrived). */
    boolean shown();

    boolean open();

    void close();
}
