package ai.resourcepack.ui.framework.platform;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which players play Bedrock Edition through Geyser. They cannot load a Java resource pack, and Geyser answers a
 * required one as loaded (so the server does not kick them), which made MC GUI open a view for them that their game
 * cannot draw. Worked out once as each player joins, through Floodgate's or Geyser's own API (by reflection, so
 * neither is a dependency), and failing both, by the UUID Floodgate gives an unlinked Bedrock player (its most
 * significant half zero, which no Java account has): that one also holds behind a proxy with Floodgate there.
 */
public final class Bedrock {
    private static final Set<UUID> PLAYERS = ConcurrentHashMap.newKeySet();

    private Bedrock() {}

    /** Whether [player] plays Bedrock Edition, as worked out when they joined. */
    public static boolean is(Player player) {
        return PLAYERS.contains(player.getUniqueId());
    }

    /** On join: works out whether [player] plays Bedrock Edition, and says so. */
    public static boolean joined(Player player) {
        UUID id = player.getUniqueId();
        boolean bedrock = id.getMostSignificantBits() == 0
                || asks("floodgate", "org.geysermc.floodgate.api.FloodgateApi", "getInstance", "isFloodgatePlayer", id)
                || asks("Geyser-Spigot", "org.geysermc.geyser.api.GeyserApi", "api", "isBedrockPlayer", id);
        if (bedrock) PLAYERS.add(id);
        else PLAYERS.remove(id);
        return bedrock;
    }

    public static void left(UUID player) {
        PLAYERS.remove(player);
    }

    /** [plugin]'s API ([api].[instance]().[test](id)), from the plugin's own class loader; false if any of it is missing. */
    private static boolean asks(String plugin, String api, String instance, String test, UUID id) {
        Plugin p = Bukkit.getPluginManager().getPlugin(plugin);
        if (p == null || !p.isEnabled()) return false;
        try {
            Class<?> type = Class.forName(api, true, p.getClass().getClassLoader());
            Object o = type.getMethod(instance).invoke(null);
            Method m = type.getMethod(test, UUID.class);
            return o != null && Boolean.TRUE.equals(m.invoke(o, id));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }
}
