package ai.resourcepack.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * UI Engine for other plugins: open a page from the pages folder for a player.
 *
 * <pre>{@code
 * UIEngine.open(player, "menu");          // plugins/UIEngine/pages/menu.html, fullscreen
 * }</pre>
 */
public final class UIEngine {
    static volatile UIEnginePlugin instance;

    private UIEngine() {}

    private static UIEnginePlugin plugin() {
        UIEnginePlugin p = instance;
        if (p == null) throw new IllegalStateException("UI Engine is not enabled");
        return p;
    }

    /**
     * Opens [page] fullscreen for [player]: at once if it is loaded, else as soon as it has loaded (pages
     * load off the server thread). False if there is no such page. Throws if there is no room for a view
     * where they are; when that only shows once the page has loaded, the player is told instead.
     */
    public static boolean open(Player player, String page) {
        return plugin().screens().open(player, page, problem -> player.sendActionBar(Component.text(problem, NamedTextColor.RED)));
    }

    /** Closes [player]'s page, if they have one open. */
    public static boolean close(Player player) {
        return plugin().screens().close(player);
    }

    /** Whether the pages folder has a page called [name]. */
    public static boolean exists(String name) {
        return plugin().pages().exists(name);
    }
}
