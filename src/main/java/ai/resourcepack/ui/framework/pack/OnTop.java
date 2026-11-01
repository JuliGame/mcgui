package ai.resourcepack.ui.framework.pack;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether MC GUI's pack is still the top one in each player's game, and ours sent again to put it back there (see
 * {@link ResourcePack#raise}). A game takes packs in the order they are sent, each above the last; a sibling plugin's
 * pack taken after ours buries ours when it replaces any of our shaders. Which sibling packs do is told only later
 * ({@link SiblingShaders}), so each player keeps the set of sibling packs (by key) taken after ours that may: buried
 * while it is not empty. No Bukkit here, the ticks are given, so the states can be tested on their own.
 */
final class OnTop {
    /** How long a raise is waited for, in ticks: as long as a view waits for it (Views.PACK_TIMEOUT). */
    static final int RAISE_TIMEOUT = 600;

    /** Sibling packs, by key, taken after ours that replace or may replace our shaders. */
    private final Map<UUID, Set<String>> buriedBy = new ConcurrentHashMap<>();
    /** Players ours was sent again to, with the tick the wait for it gives up at. */
    private final Map<UUID, Integer> raisingUntil = new ConcurrentHashMap<>();

    /** Their game took ours: it is above every pack taken before it. */
    void accepted(UUID player) {
        buriedBy.remove(player);
    }

    /** Their game took the sibling pack [by] after ours, which replaces our shaders or may. */
    void buried(UUID player, String by) {
        buriedBy.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(by);
    }

    /** The sibling pack [by] replaces none of our files: it buries nobody. */
    void harmless(String by) {
        for (Set<String> packs : buriedBy.values()) packs.remove(by);
    }

    boolean buried(UUID player) {
        Set<String> packs = buriedBy.get(player);
        return packs != null && !packs.isEmpty();
    }

    /** Whether [by] is one of the packs [player]'s ours is under. */
    boolean buriedBy(UUID player, String by) {
        Set<String> packs = buriedBy.get(player);
        return packs != null && packs.contains(by);
    }

    /** If [player]'s ours is under a sibling's, starts waiting for ours sent again: true if it is to be sent now. */
    boolean raise(UUID player, int now) {
        Set<String> packs = buriedBy.remove(player);
        if (packs == null || packs.isEmpty()) return false;
        raisingUntil.put(player, now + RAISE_TIMEOUT);
        return true;
    }

    /**
     * Whether ours, sent again, is still being waited for. A client that never answers is waited for
     * {@link #RAISE_TIMEOUT} ticks, no longer: every page after would otherwise wait as long and close.
     */
    boolean raising(UUID player, int now) {
        Integer until = raisingUntil.get(player);
        if (until == null) return false;
        if (now - until < 0) return true;
        raisingUntil.remove(player, until);
        return false;
    }

    /** Ours, sent again, has loaded or failed. */
    void raised(UUID player) {
        raisingUntil.remove(player);
    }

    void left(UUID player) {
        buriedBy.remove(player);
        raisingUntil.remove(player);
    }
}
