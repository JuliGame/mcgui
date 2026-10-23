package ai.resourcepack.ui.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * A page was put in front of a player: opened fullscreen, followed to by a link, gone back to, or shown on a
 * wall screen they came up to. Fired on the server thread as the page shows.
 */
public final class PageOpenEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String page;
    private final String screen;

    public PageOpenEvent(Player player, String page, String screen) {
        this.player = player;
        this.page = page;
        this.screen = screen;
    }

    public Player getPlayer() {
        return player;
    }

    /** The page's name in the pages folder, without {@code .html}. */
    public String getPage() {
        return page;
    }

    /** The wall screen it is on, by name; null when it is fullscreen. */
    public String getScreen() {
        return screen;
    }

    public boolean isFullscreen() {
        return screen == null;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
