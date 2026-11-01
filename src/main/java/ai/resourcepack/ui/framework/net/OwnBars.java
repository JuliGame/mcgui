package ai.resourcepack.ui.framework.net;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The action bar messages MC GUI sends a player itself (a page that cannot be found, one that failed to draw), so that
 * hiding the action bar while a page is open ({@link Pipeline#hideHud}) lets these through and nothing else. A message is
 * known by its text: the plugin says what it is about to send, and the next action bar with that text on its way to that
 * player is let by once.
 */
public final class OwnBars {
    private OwnBars() {}

    /** A message waits this long to be seen going out, which it does within a tick; a stale one never lets another by. */
    private static final long LIFE_NANOS = 5_000_000_000L;
    private static final int MOST = 8;

    private record Expected(String text, long at) {}

    private static final Map<UUID, ArrayDeque<Expected>> expected = new ConcurrentHashMap<>();

    /** MC GUI is about to send [player] an action bar of this plain [text]. */
    public static void expect(UUID player, String text) {
        ArrayDeque<Expected> queue = expected.computeIfAbsent(player, k -> new ArrayDeque<>());
        long now = System.nanoTime();
        synchronized (queue) {
            queue.removeIf(e -> now - e.at > LIFE_NANOS);
            if (queue.size() >= MOST) queue.removeFirst();
            queue.addLast(new Expected(text, now));
        }
    }

    /** Whether an action bar of [text] to [player] is one MC GUI sent: if so, it is no longer waited for. */
    static boolean take(UUID player, String text) {
        ArrayDeque<Expected> queue = expected.get(player);
        if (queue == null) return false;
        long now = System.nanoTime();
        synchronized (queue) {
            queue.removeIf(e -> now - e.at > LIFE_NANOS);
            for (var it = queue.iterator(); it.hasNext(); ) {
                if (it.next().text.equals(text)) {
                    it.remove();
                    return true;
                }
            }
        }
        return false;
    }

    static void forget(UUID player) {
        expected.remove(player);
    }
}
