package ai.resourcepack.ui.framework.net;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * The protocol version of a player's client. Where ViaVersion lets other versions join, the server sees its own
 * version in every handshake and only ViaVersion knows the client's; otherwise the handshake's is the client's.
 */
public final class ClientVersion {
    private static Object via;
    private static Method playerVersion;
    private static boolean probed;

    private ClientVersion() {}

    public static int protocol(Player player) {
        if (!probed) {
            probed = true;
            try {
                Plugin plugin = Bukkit.getPluginManager().getPlugin("ViaVersion");
                if (plugin != null && plugin.isEnabled()) {
                    Class<?> api = Class.forName("com.viaversion.viaversion.api.Via", true, plugin.getClass().getClassLoader());
                    via = api.getMethod("getAPI").invoke(null);
                    playerVersion = Class.forName("com.viaversion.viaversion.api.ViaAPI", true, plugin.getClass().getClassLoader())
                            .getMethod("getPlayerVersion", UUID.class);
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                via = null;
            }
        }
        if (via != null) {
            try {
                int v = (int) playerVersion.invoke(via, player.getUniqueId());
                if (v > 0) return v;
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Not known to ViaVersion (yet): the handshake's, below.
            }
        }
        return ai.resourcepack.ui.framework.platform.Platform.protocol(player);
    }
}
