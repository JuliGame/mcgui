package ai.resourcepack.ui;

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

    /** Opens [page] fullscreen for [player]. False if there is no such page; throws if there is no room for a view where they are. */
    public static boolean open(Player player, String page) {
        return plugin().screens().open(player, page);
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
