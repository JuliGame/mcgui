package ai.resourcepack.ui.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.Map;

/**
 * A player clicked (or pressed the {@code data-key} of) something on a page that does something: a link, a
 * button, a field, a form's submit button, or any element with a {@code data-event}, which does nothing
 * else and is there for plugins. Fired on the server thread before it acts; cancel it and nothing happens.
 *
 * <pre>{@code
 * <button data-event="buy" data-item="bread">Buy</button>
 *
 * @EventHandler
 * public void onClick(PageClickEvent e) {
 *     if ("buy".equals(e.getEvent())) shop.buy(e.getPlayer(), e.getAttribute("data-item"));
 * }
 * }</pre>
 */
public final class PageClickEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final String page, screen, tag, text;
    private final Map<String, String> attributes, values;
    private boolean cancelled;

    public PageClickEvent(Player player, String page, String screen, String tag, Map<String, String> attributes, String text, Map<String, String> values) {
        this.player = player;
        this.page = page;
        this.screen = screen;
        this.tag = tag;
        this.attributes = Map.copyOf(attributes);
        this.text = text;
        this.values = Map.copyOf(values);
    }

    /** Who clicked: on a shared wall screen, whoever it was. */
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

    /** The element's {@code data-event}, or null if it has none. */
    public String getEvent() {
        return attributes.get("data-event");
    }

    /** The element that acts (the button, not the icon inside it): its tag, lower case. */
    public String getTag() {
        return tag;
    }

    /** Its {@code id}, or null. */
    public String getId() {
        return attributes.get("id");
    }

    /** One of its attributes, as filled in for the player ({@code {player}} and the like); null if it has none. */
    public String getAttribute(String name) {
        return attributes.get(name);
    }

    /** All of its attributes, as filled in for the player. */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    /** Its text, as shown. */
    public String getText() {
        return text;
    }

    /**
     * The fields of the form it is in (or sends), else of the whole page, by name: what {@code {form.<name>}}
     * would be in a command. Several ticked boxes of one name are joined with commas.
     */
    public Map<String, String> getValues() {
        return values;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
