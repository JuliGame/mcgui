package ai.resourcepack.ui.framework;

import ai.resourcepack.ui.framework.net.ClientVersion;
import ai.resourcepack.ui.framework.net.MapSender;
import ai.resourcepack.ui.framework.net.Nms;
import ai.resourcepack.ui.framework.net.ServerWire;
import ai.resourcepack.ui.framework.pack.Shaders;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.logging.Logger;

/**
 * {@code /mcgui debug <player> trace on}: where the time of each page or view that player opens goes, one log line per
 * open, in ms from the command (see the README's "If menus open slowly"). Off, which it is unless someone turns it on,
 * every hook is one read of {@link #on} and returns: no clock is read and nothing is made. On, only the traced players'
 * opens are timed, and each open costs what its line does.
 */
public final class OpenTrace {
    /** Whether anybody is traced: all a hook reads while nobody is. */
    private static volatile boolean on;
    private static final Set<UUID> traced = ConcurrentHashMap.newKeySet();
    /** Each traced player's open still being timed: begun, and not yet logged. */
    private static final Map<UUID, Open> opens = new ConcurrentHashMap<>();
    private static volatile Env env;
    private static final String COUNTER = "uiengine_trace";

    private record Env(Logger log, Nms nms, ServerWire wire, MapSender sender) {}

    private OpenTrace() {}

    static void start(Logger log, Nms nms, ServerWire wire, MapSender sender) {
        env = new Env(log, nms, wire, sender);
    }

    static void stop() {
        env = null;
        on = false;
        traced.clear();
        for (Open o : opens.values()) o.uncount();
        opens.clear();
    }

    /** Turns tracing [player]'s opens on or off. */
    public static void trace(UUID player, boolean trace) {
        if (trace) traced.add(player);
        else {
            traced.remove(player);
            Open o = opens.remove(player);
            if (o != null) o.uncount();
        }
        on = !traced.isEmpty();
    }

    /** Whether anybody is traced. */
    public static boolean on() {
        return on;
    }

    public static boolean traced(UUID player) {
        return on && traced.contains(player);
    }

    /** [player]'s open being timed, or null: the hooks' one check. Nothing is made and no clock read while nobody is traced. */
    public static Open of(UUID player) {
        return on && player != null ? opens.get(player) : null;
    }

    /**
     * An open of [what] for [player] starts now, if they are traced (else null): the time every phase is measured from.
     * An open of theirs still being timed is logged as it stands, cut short by this one.
     */
    public static Open begin(Player player, String what) {
        if (!on || !traced.contains(player.getUniqueId())) return null;
        Env e = env;
        if (e == null) return null;
        Open o = new Open(player, what, e);
        Open old = opens.put(player.getUniqueId(), o);
        if (old != null) old.finish("another open began before it was shown");
        o.count(e);
        return o;
    }

    /** One open being timed. Its phases are set from the server thread, render threads and the connection's thread. */
    public static final class Open {
        public static final int LOADED = 0, QUEUED = 1, STARTED = 2, DRAWN = 3, KEPT = 4, FIRST_WRITE = 5, LAST_WRITE = 6, DELIVERED = 7,
                TAKE_SENT = 8, TAKE_PONG = 9, SHOWN = 10, CONFIRMED = 11;
        private static final int PHASES = 12;

        private final UUID player;
        private final String name, what, client, compression;
        private final int ping;
        private final long held;
        private final long start = System.nanoTime();
        /** Each phase's nanoTime, 0 until it happens; the first time counts (but LAST_WRITE's last). */
        private final AtomicLongArray at = new AtomicLongArray(PHASES);
        private final AtomicLong raw = new AtomicLong(), wire = new AtomicLong(), patches = new AtomicLong(), shared = new AtomicLong(),
                connection = new AtomicLong();
        private volatile String kind = "page";
        private volatile Object view;
        private volatile boolean loadedAlready;
        private volatile Counter counter;
        private volatile boolean finished;
        private volatile String note;

        /** Something about this open the line should say (any thread). */
        public void note(String note) {
            this.note = note;
        }

        private Open(Player p, String what, Env e) {
            this.player = p.getUniqueId();
            this.name = p.getName();
            this.what = what;
            this.ping = p.getPing();
            this.held = e.sender.held(player);
            this.client = client(p);
            this.compression = e.wire.compression(p);
        }

        /** Phase [phase] happens now (any thread); only its first time counts. */
        public void mark(int phase) {
            at.compareAndSet(phase, 0, Math.max(1, System.nanoTime()));
        }

        /** The page was loaded already when the open began. */
        public void loadedAlready() {
            loadedAlready = true;
            mark(LOADED);
        }

        /** What is opening: "fullscreen", "overlay" and so on, and the view it is (so its closing ends this). */
        public void view(Object view, String kind) {
            this.view = view;
            this.kind = kind;
        }

        public boolean of(Object view) {
            return this.view == view;
        }

        /** Whether a view is opening for it already: another view opened after it is an open of its own. */
        public boolean attached() {
            return view != null;
        }

        /** Patches written to the connection (its thread): [raw] map bytes, [wire] of them compressed once for all ([sharedPatches] patches). */
        public void written(long raw, long wire, int patches, int sharedPatches) {
            if (at.get(SHOWN) != 0) return;
            long now = Math.max(1, System.nanoTime());
            at.compareAndSet(FIRST_WRITE, 0, now);
            at.set(LAST_WRITE, now);
            this.raw.addAndGet(raw);
            this.wire.addAndGet(wire);
            this.patches.addAndGet(patches);
            this.shared.addAndGet(sharedPatches);
        }

        /** The client answered the ping sent after the view was shown (its thread): logged now. */
        public void confirmed() {
            mark(CONFIRMED);
            finish(null);
        }

        /** Logs this open's line, once; [cut]: why it ended before the client confirmed the view, or null. */
        public void finish(String cut) {
            if (finished) return;
            finished = true;
            opens.remove(player, this);
            uncount();
            Env e = env;
            if (e != null) e.log.info(line(cut));
        }

        private String ms(int phase) {
            long t = at.get(phase);
            return t == 0 ? "-" : Long.toString((t - start) / 1_000_000);
        }

        private boolean has(int phase) {
            return at.get(phase) != 0;
        }

        String line(String cut) {
            StringBuilder b = new StringBuilder(512);
            b.append("Open trace: ").append(name).append(' ').append(what).append(" (").append(kind).append(", ")
                    .append(held == 0 ? "first open: the client holds none of its maps" : "reopen: the client holds " + held + " maps")
                    .append("), ms from the command:");
            b.append(" page loaded ").append(ms(LOADED)).append(loadedAlready ? " (was loaded)" : "");
            if (has(KEPT) && !has(QUEUED)) b.append(", picture kept from an earlier open ").append(ms(KEPT));
            else b.append(", render queued ").append(ms(QUEUED)).append(", started ").append(ms(STARTED)).append(", done ").append(ms(DRAWN));
            b.append(", tiles written ");
            if (patches.get() == 0) b.append("none (the client had them all)");
            else {
                b.append(ms(FIRST_WRITE)).append(" to ").append(ms(LAST_WRITE)).append(" (").append(patches.get()).append(" patches, ")
                        .append(kb(raw.get())).append(" raw; ");
                long s = shared.get(), n = patches.get();
                if (s == 0) b.append("compressed by the connection");
                else b.append(s == n ? "all" : Long.toString(s)).append(" compressed once for all to ").append(kb(wire.get()))
                        .append(s == n ? "" : ", the rest by the connection");
                b.append(')');
            }
            b.append(", all written ").append(ms(DELIVERED));
            if (has(TAKE_SENT)) b.append(", take sent ").append(ms(TAKE_SENT)).append(", its pong ").append(ms(TAKE_PONG));
            b.append(kind.startsWith("overlay") ? ", panel and frames sent " : ", frames and camera sent ").append(ms(SHOWN));
            b.append(", client confirmed ").append(ms(CONFIRMED));
            long out = connection.get();
            if (out >= 0) b.append(" | connection sent ").append(kb(out)).append(" in all meanwhile");
            if (note != null) b.append(" | ").append(note);
            if (cut != null) b.append(" | cut short: ").append(cut);
            b.append(" | ping ").append(ping).append(" ms | connection ").append(compression).append(" | client ").append(client)
                    .append(" | server ").append(server()).append(" | proxy: ").append(proxy());
            return b.toString();
        }

        private static String kb(long bytes) {
            return bytes < 10_240 ? bytes + " B" : String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }

        /** Counts what the connection writes from now on, in front of its framing: what goes on the wire. */
        private void count(Env e) {
            connection.set(-1);
            Channel channel = e.nms.channel(Bukkit.getPlayer(player));
            if (channel == null) return;
            Counter c = new Counter(this);
            counter = c;
            try {
                channel.eventLoop().execute(() -> {
                    ChannelPipeline p = channel.pipeline();
                    if (counter != c || p.get("prepender") == null) return;
                    if (p.get(COUNTER) != null) p.remove(COUNTER);
                    p.addBefore("prepender", COUNTER, c);
                    c.channel = channel;
                    connection.compareAndSet(-1, 0);
                });
            } catch (RejectedExecutionException ignored) {
                // Closing.
            }
        }

        private void uncount() {
            Counter c = counter;
            counter = null;
            if (c == null) return;
            c.open = null;
            Channel channel = c.channel;
            if (channel == null) return;
            try {
                channel.eventLoop().execute(() -> {
                    if (channel.pipeline().get(COUNTER) == c) channel.pipeline().remove(COUNTER);
                });
            } catch (RejectedExecutionException ignored) {
                // Closing: the handler goes with the channel.
            }
        }
    }

    /** Adds up the bytes a connection writes, framed: what leaves for the socket. On the connection's thread. */
    private static final class Counter extends ChannelOutboundHandlerAdapter {
        volatile Open open;
        volatile Channel channel;

        Counter(Open open) {
            this.open = open;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Open o = open;
            if (o != null) {
                if (msg instanceof ByteBuf b) o.connection.addAndGet(b.readableBytes());
                else if (msg instanceof ByteBufHolder h) o.connection.addAndGet(h.content().readableBytes());
            }
            ctx.write(msg, promise);
        }
    }

    /** The client's version as the trace says it. */
    private static String client(Player p) {
        int protocol = ClientVersion.protocol(p);
        Shaders.Dialect d = Shaders.Dialect.of(protocol);
        String version = d == null ? "unknown version" : d.name().substring(1).replace('_', '.');
        return version + " (protocol " + protocol + (Bukkit.getPluginManager().getPlugin("ViaVersion") != null ? ", ViaVersion on" : "") + ")";
    }

    /** The server software, and Paper's average tick time where it can be read. */
    private static String server() {
        String s = Bukkit.getName() + " " + Bukkit.getVersion();
        try {
            double tick = (double) Bukkit.getServer().getClass().getMethod("getAverageTickTime").invoke(Bukkit.getServer());
            s += String.format(Locale.ROOT, ", ticks %.1f ms", tick);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Not Paper.
        }
        return s;
    }

    private static volatile String proxy;

    /** Whether this server takes players from a proxy: Velocity's modern forwarding (Paper) or BungeeCord's (Spigot's setting). */
    static String proxy() {
        String known = proxy;
        if (known != null) return known;
        ClassLoader server = Bukkit.getServer().getClass().getClassLoader();
        StringBuilder found = new StringBuilder();
        try {
            Class<?> global = Class.forName("io.papermc.paper.configuration.GlobalConfiguration", true, server);
            Object config = global.getMethod("get").invoke(null);
            Object proxies = global.getField("proxies").get(config);
            Object velocity = proxies.getClass().getField("velocity").get(proxies);
            if (velocity.getClass().getField("enabled").getBoolean(velocity)) found.append("Velocity forwarding");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Not Paper, or not this Paper's configuration.
        }
        try {
            if (Class.forName("org.spigotmc.SpigotConfig", true, server).getField("bungee").getBoolean(null)) {
                found.append(found.isEmpty() ? "" : ", ").append("BungeeCord forwarding");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // No Spigot configuration.
        }
        return proxy = found.isEmpty() ? "none detected" : found.toString();
    }
}
