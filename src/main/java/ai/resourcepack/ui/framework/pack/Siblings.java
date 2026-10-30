package ai.resourcepack.ui.framework.pack;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * ResourcePack AI's plugins tell each other which resource packs are theirs the way their shared library's
 * {@code SiblingPacks} does: each registers a {@code Predicate<UUID>} service (a JDK type, so it survives every
 * plugin relocating that library) answering whether a pack id is its own, and only providers whose class is one of
 * theirs ({@code ai.resourcepack.}) count. Dialog Engine sends its drawn-dialog pack again, on top, after any other
 * pack reaches a player, except a sibling's: so registering keeps it from burying ours again each time ours is sent
 * again (see {@link ResourcePack#raise}).
 */
public final class Siblings implements Predicate<UUID> {
    private Siblings() {}

    @Override
    public boolean test(UUID pack) {
        return ResourcePack.PACK_ID.equals(pack);
    }

    /** Registers [plugin]'s pack as a sibling's. The server drops the registration when the plugin is disabled. */
    public static void register(Plugin plugin) {
        Bukkit.getServicesManager().register(Predicate.class, new Siblings(), plugin, ServicePriority.Normal);
    }

    /** Whether [pack] is another of ResourcePack AI's plugins' (not [self]'s own). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean fromSibling(Plugin self, UUID pack) {
        if (pack == null) return false;
        for (RegisteredServiceProvider<Predicate> r : Bukkit.getServicesManager().getRegistrations(Predicate.class)) {
            Object provider = r.getProvider();
            if (r.getPlugin() == self || !provider.getClass().getName().startsWith("ai.resourcepack.")) continue;
            try {
                if (((Predicate<UUID>) provider).test(pack)) return true;
            } catch (RuntimeException e) {
                // Their answer, not ours to fail on.
            }
        }
        return false;
    }
}
