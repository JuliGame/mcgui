package ai.resourcepack.ui.framework.platform;

import ai.resourcepack.common.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Which thread runs what.
 *
 * <p>On Spigot and Paper, one: the main thread, and each call here is the Bukkit scheduler call it replaces, so
 * nothing about those servers changes. On Folia every region of the world ticks on its own thread, and a player
 * or an entity may only be touched from the thread of the region it is in. So there, what concerns one player
 * (their view, what they aim at on a wall, their seated page) runs on their thread, every tick
 * ({@link PlayerTicks}), and what concerns nobody in particular (sending maps, walls' sessions) on the global
 * region's. Those run side by side, and all of MC GUI was written for one thread: so on Folia every way in, a
 * task, an event, a command, holds one lock ({@link #locked}), and MC GUI still sees one thread at a time. Its own
 * work in a tick is small (pages are drawn off the server's threads), so the regions seldom wait on each other.
 */
public final class Threads {
    private Threads() {}

    private static final ReentrantLock LOCK = new ReentrantLock();
    /** Folia's tick count: it has none of its own for the whole server, so the global tick counts here. */
    private static final AtomicInteger TICKS = new AtomicInteger();

    public static boolean folia() {
        return Tasks.folia();
    }

    /** Runs [task] holding MC GUI's lock on Folia; elsewhere the main thread is the lock, and it simply runs. */
    public static void locked(Runnable task) {
        if (!folia() || LOCK.isHeldByCurrentThread()) {
            task.run();
            return;
        }
        LOCK.lock();
        try {
            task.run();
        } finally {
            LOCK.unlock();
        }
    }

    /** [task], to run holding the lock (see {@link #locked}). */
    public static Runnable guarded(Runnable task) {
        return folia() ? () -> locked(task) : task;
    }

    /** Whether this thread may touch [entity] now. */
    public static boolean owns(Entity entity) {
        return Tasks.owns(entity);
    }

    /** On the next tick, on the main thread or Folia's global region. */
    public static Tasks.Handle global(Plugin plugin, Runnable task) {
        return Tasks.global(plugin, guarded(task));
    }

    public static Tasks.Handle globalLater(Plugin plugin, Runnable task, long delay) {
        return Tasks.globalLater(plugin, guarded(task), delay);
    }

    public static Tasks.Handle globalTimer(Plugin plugin, Runnable task, long delay, long period) {
        return Tasks.globalTimer(plugin, guarded(task), delay, period);
    }

    /** On the next tick, on the thread that owns [entity]; never if it has gone. */
    public static Tasks.Handle on(Plugin plugin, Entity entity, Runnable task) {
        return Tasks.on(plugin, entity, guarded(task));
    }

    public static Tasks.Handle onLater(Plugin plugin, Entity entity, Runnable task, long delay) {
        return Tasks.onLater(plugin, entity, guarded(task), delay);
    }

    /** At once if this thread owns [entity], else as {@link #on}. */
    public static void now(Plugin plugin, Entity entity, Runnable task) {
        if (owns(entity)) locked(task);
        else on(plugin, entity, task);
    }

    /** At once if this is the main thread or Folia's global region, else on the next tick there. */
    public static void globalNow(Plugin plugin, Runnable task) {
        if (Tasks.global()) locked(task);
        else global(plugin, task);
    }

    /** Off the server's threads: never holding the lock, which is for the server's own. */
    public static Tasks.Handle async(Plugin plugin, Runnable task) {
        return Tasks.async(plugin, task);
    }

    public static Tasks.Handle asyncTimer(Plugin plugin, Runnable task, long delay, long period) {
        return Tasks.asyncTimer(plugin, task, delay, period);
    }

    /**
     * Where work handed back to the server goes: [main] (the server's own executor) on Spigot and Paper, and on
     * Folia the global region, which has no executor of its own.
     */
    public static Executor server(Plugin plugin, Executor main) {
        return folia() ? task -> global(plugin, task) : main;
    }

    /** As {@link #server}, but on Folia [player]'s thread: for work about them. */
    public static Executor player(Plugin plugin, Player player, Executor main) {
        return folia() ? task -> now(plugin, player, task) : main;
    }

    /** Folia's tick count goes up here, once a tick, from the global region. */
    public static void countTick() {
        TICKS.incrementAndGet();
    }

    /** On Folia, makes {@link Platform#tick} read the count {@link #countTick} keeps. */
    public static void useFoliaTicks() {
        if (folia()) Platform.useTicks(TICKS::get);
    }

    /**
     * Registers [listener]'s handlers, as {@code registerEvents} does; on Folia each runs holding the lock, as
     * events come from every region's thread.
     */
    public static void register(Listener listener, Plugin plugin) {
        if (!folia()) {
            Bukkit.getPluginManager().registerEvents(listener, plugin);
            return;
        }
        for (Class<?> c = listener.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                EventHandler h = m.getAnnotation(EventHandler.class);
                if (h == null || m.isBridge() || m.isSynthetic() || m.getParameterCount() != 1) continue;
                Class<?> type = m.getParameterTypes()[0];
                if (!Event.class.isAssignableFrom(type)) continue;
                m.setAccessible(true);
                @SuppressWarnings("unchecked") Class<? extends Event> eventType = (Class<? extends Event>) type;
                Bukkit.getPluginManager().registerEvent(eventType, listener, h.priority(), (l, event) -> {
                    if (!eventType.isInstance(event)) return;
                    locked(() -> {
                        try {
                            m.invoke(l, event);
                        } catch (InvocationTargetException e) {
                            Throwable cause = e.getCause();
                            if (cause instanceof RuntimeException r) throw r;
                            if (cause instanceof Error err) throw err;
                            throw new IllegalStateException(cause);
                        } catch (IllegalAccessException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                }, plugin, h.ignoreCancelled());
            }
        }
    }

    /**
     * On Folia, something to run for every online player each tick, on their own thread and holding the lock; from
     * when they join until they leave. Nothing at all elsewhere, where the main tick does it for everybody.
     */
    public static final class PlayerTicks implements Listener {
        private final Plugin plugin;
        private final Consumer<Player> tick;
        private final Map<UUID, Tasks.Handle> running = new HashMap<>();

        private PlayerTicks(Plugin plugin, Consumer<Player> tick) {
            this.plugin = plugin;
            this.tick = tick;
        }

        /** Starts [tick] for everyone on Folia; null elsewhere. */
        public static PlayerTicks start(Plugin plugin, Consumer<Player> tick) {
            if (!folia()) return null;
            PlayerTicks t = new PlayerTicks(plugin, tick);
            register(t, plugin);
            for (Player p : Bukkit.getOnlinePlayers()) t.begin(p);
            return t;
        }

        private void begin(Player p) {
            Tasks.Handle old = running.put(p.getUniqueId(), Tasks.onTimer(plugin, p, guarded(() -> {
                try {
                    tick.accept(p);
                } catch (RuntimeException | LinkageError e) {
                    plugin.getLogger().log(Level.SEVERE, "A tick for " + p.getName() + " failed", e);
                }
            }), 1, 1));
            if (old != null) old.cancel();
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent e) {
            begin(e.getPlayer());
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent e) {
            Tasks.Handle h = running.remove(e.getPlayer().getUniqueId());
            if (h != null) h.cancel();
        }

        public void stop() {
            HandlerList.unregisterAll(this);
            running.values().forEach(Tasks.Handle::cancel);
            running.clear();
        }
    }
}
