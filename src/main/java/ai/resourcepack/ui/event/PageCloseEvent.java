package ai.resourcepack.ui.event;

import ai.resourcepack.ui.framework.api.CloseReason;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A player's fullscreen page closed, and they are back where they were. Fired on the server thread. */
public final class PageCloseEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String page;
    private final CloseReason reason;

    public PageCloseEvent(Player player, String page, CloseReason reason) {
        this.player = player;
        this.page = page;
        this.reason = reason;
    }

    public Player getPlayer() {
        return player;
    }

    /** The page they had open, by name; null if it had not loaded yet. */
    public String getPage() {
        return page;
    }

    /** Why: they got up, a plugin or command closed it, something interrupted it (they left, died, were hurt), or the server stopped. */
    public CloseReason getReason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
