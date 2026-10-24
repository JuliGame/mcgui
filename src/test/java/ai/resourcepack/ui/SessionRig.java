package ai.resourcepack.ui;

import ai.resourcepack.ui.html.Page;
import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.api.Cpu;
import ai.resourcepack.ui.framework.render.Palette;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Real {@link Session}s with no Minecraft server: a stand-in Bukkit server (the tick and the online count are the
 * test's to set, everything else answers nothing), a plugin made without its constructor and given only what a
 * session reads (its settings, pages, first pictures and server thread), one thread standing for the server thread,
 * and the framework's {@link Cpu} pool as the render threads, as in the plugin. Everything a session is told goes
 * through {@link #onServer}, as on a server.
 */
final class SessionRig implements AutoCloseable {
    static final AtomicInteger TICK = new AtomicInteger(), ONLINE = new AtomicInteger(3);
    static final Palette PALETTE = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));
    /** Viewers who are online, by id, with their names: their pages are filled for them ({@link #player}). Everybody else is offline. */
    static final java.util.Map<UUID, String> PLAYERS = new java.util.concurrent.ConcurrentHashMap<>();
    /** Every command run as the console, in order. */
    static final java.util.List<String> COMMANDS = new java.util.concurrent.CopyOnWriteArrayList<>();

    static {
        // Set straight into Bukkit: setServer would look for the build information of a real server.
        if (Bukkit.getServer() == null) {
            try {
                Field f = Bukkit.class.getDeclaredField("server");
                f.setAccessible(true);
                f.set(null, stub(Server.class));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Answers what Session and Placeholders ask the server; anything else gets nothing (a stub of its own for an interface). */
    private static Object stub(Class<?> type) {
        return Proxy.newProxyInstance(SessionRig.class.getClassLoader(), new Class<?>[]{type}, (self, m, args) -> switch (m.getName()) {
            case "getCurrentTick" -> TICK.get();
            case "getOnlinePlayers" -> Collections.nCopies(ONLINE.get(), null);
            case "getMaxPlayers" -> 20;
            // Every viewer is offline, unless the test named them: a page is filled for nobody in particular, as for anyone on a public page.
            case "getPlayer" -> args[0] instanceof UUID id && PLAYERS.containsKey(id) ? player(id, PLAYERS.get(id)) : null;
            case "getPlayerExact" -> null;
            case "dispatchCommand" -> COMMANDS.add((String) args[1]);
            case "getLogger" -> Logger.getLogger("SessionRig");
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == args[0];
            case "toString" -> type.getSimpleName() + " stub";
            default -> {
                Class<?> r = m.getReturnType();
                if (r == boolean.class) yield false;
                if (r == int.class || r == long.class || r == short.class || r == byte.class) yield 0;
                if (r == double.class || r == float.class) yield 0.0;
                if (r == String.class) yield "stub";
                if (Collection.class.isAssignableFrom(r)) yield java.util.List.of();
                if (r.isInterface()) yield stub(r);
                yield null;
            }
        });
    }

    /** An online player as Placeholders and Session read one: a name, an id, and the rest the defaults of somebody who just joined. */
    static org.bukkit.entity.Player player(UUID id, String name) {
        return (org.bukkit.entity.Player) Proxy.newProxyInstance(SessionRig.class.getClassLoader(), new Class<?>[]{org.bukkit.entity.Player.class}, (self, m, args) -> switch (m.getName()) {
            case "getName" -> name;
            case "getUniqueId" -> id;
            case "displayName" -> net.kyori.adventure.text.Component.text(name);
            case "getLocation" -> new org.bukkit.Location(null, 0, 64, 0);
            case "getHealth" -> 20.0;
            case "getGameMode" -> org.bukkit.GameMode.SURVIVAL;
            case "isOnline" -> true;
            case "hashCode" -> id.hashCode();
            case "equals" -> self == args[0];
            case "toString" -> "player " + name;
            default -> m.invoke(stub(org.bukkit.entity.Player.class), args);
        });
    }

    final ThreadPoolExecutor server = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "rig-server");
        t.setDaemon(true);
        return t;
    });
    final Renders renderers;
    /** Tasks handed to the server thread and the render threads, and those of them finished. */
    private final AtomicLong posted = new AtomicLong(), ran = new AtomicLong(), queued = new AtomicLong(), drawn = new AtomicLong();
    /** CPU (ns) and bytes allocated on the server thread by what sessions handed it, and by {@link #open}. */
    final AtomicLong serverCpu = new AtomicLong(), serverBytes = new AtomicLong();
    /** CPU (ns) of the render threads' jobs, by kind. */
    final java.util.Map<Renders.Kind, AtomicLong> renderCpu = new java.util.concurrent.ConcurrentHashMap<>();
    /** What sessions are given as the server thread: the rig's thread, counting what it is handed. */
    final java.util.concurrent.Executor serverThread = task -> {
        posted.incrementAndGet();
        server.execute(() -> {
            long cpu = THREADS.getCurrentThreadCpuTime(), bytes = THREADS.getCurrentThreadAllocatedBytes();
            try {
                task.run();
            } finally {
                serverCpu.addAndGet(THREADS.getCurrentThreadCpuTime() - cpu);
                serverBytes.addAndGet(THREADS.getCurrentThreadAllocatedBytes() - bytes);
                ran.incrementAndGet();
            }
        });
    };
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    final PageStore pages;
    final Path dir;

    /** [renderThreads] is the {@link Cpu} budget while the rig is open; {@link #close} puts the default back. */
    SessionRig(Path dir, int renderThreads) {
        this.dir = dir;
        Cpu.configure(renderThreads);
        renderers = new Renders() {
            @Override
            public void execute(Kind kind, Runnable job) {
                queued.incrementAndGet();
                super.execute(kind, () -> {
                    long cpu = THREADS.getCurrentThreadCpuTime();
                    try {
                        job.run();
                    } finally {
                        renderCpu.computeIfAbsent(kind, k -> new AtomicLong()).addAndGet(THREADS.getCurrentThreadCpuTime() - cpu);
                        drawn.incrementAndGet();
                    }
                });
            }
        };
        pages = new PageStore(dir, new Page.Loader(false, null), Logger.getLogger("SessionRig"));
    }

    /** A plugin as a session sees it, keeping up to [entries] first pictures (0: none, so every session draws its own). */
    MCGUIPlugin plugin(int entries) {
        return plugin(entries, -1);
    }

    /** As {@link #plugin(int)}, drawing [ahead] hover targets ahead (-1: the config's default). */
    MCGUIPlugin plugin(int entries, int ahead) {
        return plugin(entries, ahead, true);
    }

    /** As {@link #plugin(int, int)}, sharing personal pages' pictures between players where their own values are not if [personal]. */
    MCGUIPlugin plugin(int entries, int ahead, boolean personal) {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            MCGUIPlugin p = (MCGUIPlugin) ((sun.misc.Unsafe) f.get(null)).allocateInstance(MCGUIPlugin.class);
            YamlConfiguration cfg = new YamlConfiguration();
            // No time limit: the machine is shared, and a slow render here is not a page that cannot be drawn.
            cfg.set("render-timeout", 0);
            cfg.set("first-pictures.entries", entries);
            if (ahead >= 0) cfg.set("hover-ahead", ahead);
            cfg.set("sharing.personal-pages", personal);
            set(p, MCGUIPlugin.class, "cfg", cfg);
            set(p, MCGUIPlugin.class, "pages", pages);
            set(p, MCGUIPlugin.class, "serverThread", serverThread);
            set(p, MCGUIPlugin.class, "pictures", new FirstPictures(entries, 512L << 20));
            set(p, MCGUIPlugin.class, "frames", new AnimFrames());
            set(p, MCGUIPlugin.class, "repaints", new ai.resourcepack.ui.html.SharedRepaints(256L << 20));
            set(p, JavaPlugin.class, "logger", Logger.getLogger("SessionRig"));
            return p;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object o, Class<?> owner, String name, Object value) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    static FirstPictures pictures(MCGUIPlugin plugin) {
        return plugin.firstPictures();
    }

    /** A new session on a [cols] x [rows] canvas showing [page], made and opened on the server thread, as a wall makes one. */
    Session open(MCGUIPlugin plugin, String page, int cols, int rows, double designHeight) {
        return open(plugin, page, cols, rows, designHeight, UUID.randomUUID());
    }

    /** As {@link #open(MCGUIPlugin, String, int, int, double)}, for viewer [id] (online if in {@link #PLAYERS}). */
    Session open(MCGUIPlugin plugin, String page, int cols, int rows, double designHeight, UUID id) {
        return onServer(() -> {
            long cpu = THREADS.getCurrentThreadCpuTime(), bytes = THREADS.getCurrentThreadAllocatedBytes();
            Session s = new Session(plugin, renderers, new Canvas(PALETTE, cols, rows), id, designHeight);
            s.open(page, null, false);
            serverCpu.addAndGet(THREADS.getCurrentThreadCpuTime() - cpu);
            serverBytes.addAndGet(THREADS.getCurrentThreadAllocatedBytes() - bytes);
            return s;
        });
    }

    <T> T onServer(Callable<T> work) {
        try {
            return server.submit(work).get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    void onServer(Runnable work) {
        onServer(() -> {
            work.run();
            return null;
        });
    }

    /** Waits until nothing is being drawn and the server thread has nothing left to do. */
    void quiet() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(300);
        while (System.nanoTime() < deadline) {
            long p = posted.get(), q = queued.get();
            if (ran.get() == p && drawn.get() == q) {
                // Twice, with the server thread run in between: nothing was in flight between the two looks.
                onServer(() -> null);
                if (posted.get() == p && ran.get() == p && queued.get() == q && drawn.get() == q) return;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException("never went quiet");
    }

    @Override
    public void close() {
        server.shutdownNow();
        try {
            renderers.close(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Cpu.configure(0);
        pages.close();
    }
}
