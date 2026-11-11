package ai.resourcepack.ui.framework.pack;

import org.bukkit.Bukkit;

/**
 * How MC GUI asks for another plugin's (or the server's) resource pack. Some pack hosts give a pack only to the game:
 * ResourcePack AI's, where RP Engine uploads its packs by default, answers anything whose User-Agent does not start
 * with {@code Minecraft Java/} with a 403. MC GUI reads these packs on the client's behalf, the same link the client
 * was sent, so it asks the way the client does, and says it is MC GUI after.
 */
final class PackFetch {
    static final String USER_AGENT = "Minecraft Java/" + minecraftVersion() + " MCGUI";

    private PackFetch() {
    }

    private static String minecraftVersion() {
        try {
            String v = Bukkit.getBukkitVersion();
            int dash = v.indexOf('-');
            return dash > 0 ? v.substring(0, dash) : v;
        } catch (RuntimeException | LinkageError e) {
            return "unknown";
        }
    }
}
