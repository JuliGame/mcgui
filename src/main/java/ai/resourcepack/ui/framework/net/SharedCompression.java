package ai.resourcepack.ui.framework.net;

import ai.resourcepack.ui.framework.api.Cpu;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandler;
import io.netty.channel.ChannelPipeline;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Map patches encoded and compressed once, off the connections' threads, and the same bytes written to every
 * connection that would have made exactly those bytes itself. Without this each viewer's connection encodes
 * and deflates its own copy of every patch on Paper's netty threads, which also read every player's
 * movement: about 0.17 ms a full tile, so 50 players watching one wall change cost 50 times that.
 *
 * <p>Paper's outbound pipeline, from the tail (where a packet object goes in) to the socket, is: our own
 * handlers, {@code hackfix}, {@code unbundler} (a bundle into delimiter, packets, delimiter), {@code encoder}
 * (packet object into id and fields), {@code compress} (below the threshold a 0 and the bytes as they are,
 * otherwise the uncompressed length and a zlib stream), {@code prepender} (the frame length), then
 * {@code encrypt} once logged in online. The bytes here are what {@code compress} passes on, written from its
 * context so they go through {@code prepender} and the connection's own encryption, in order with every other
 * packet because both are written on the connection's own thread.
 *
 * <p>Only where nothing else can tell the difference: a connection is used only while its channel is netty's
 * own, its {@code compress} and {@code prepender} are Minecraft's, its encoder is in the game protocol, and no
 * handler that is not Minecraft's or ours sees packets on their way out. Anything that reads or rewrites
 * packets (ViaVersion, ProtocolLib, PacketEvents) would never see the map patches written past it, so with
 * any of them in a connection that connection gets packet objects as before, and that is said once in the log.
 */
public final class SharedCompression implements AutoCloseable {
    /** The packets written instead, where a connection cannot take the compressed bytes. */
    public interface Packets {
        Object patch(int mapId, int x, int y, int w, int h, byte[] colors);

        Object bundle(List<?> packets);
    }

    /**
     * Marks a handler of ours that lets map patches through as they are, so a connection with it in can still
     * be written compressed bytes.
     */
    interface LeavesMapsAlone {}

    /**
     * One patch as the {@code compress} handler of a connection with [threshold] passes it on: the
     * uncompressed length (0 below the threshold) and the zlib stream (or the bytes as they are). Never
     * changed once made, so any number of connections may write it.
     */
    static final class Compressed {
        final byte[] frame;
        final int threshold;
        /** What it was made from, kept to make the packet again for a connection that cannot take it. */
        final int mapId, x, y, w, h;
        final byte[] tile;
        long used;

        Compressed(byte[] frame, int threshold, int mapId, int x, int y, int w, int h, byte[] tile) {
            this.frame = frame;
            this.threshold = threshold;
            this.mapId = mapId;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.tile = tile;
        }

        Object packet(Packets packets) {
            return packets.patch(mapId, x, y, w, h, rectangle(tile, x, y, w, h));
        }
    }

    /** What makes one patch: the tile by identity (never changed once published), the rectangle and the map. */
    private record Key(byte[] tile, int x, int y, int w, int h, int mapId, int threshold) {}

    /**
     * A connection that can take compressed bytes: its threshold, the encoder of its game protocol, and that
     * protocol's bundle delimiter (one instance, the only one its encoder takes).
     */
    private record Route(int threshold, Object codec, Object delimiter) {}

    private record Checked(Route route, long at) {}

    /** How long a patch is kept after anyone last asked for it: players watching together ask within a pass or two. */
    private static final long IDLE_NANOS = 1_000_000_000L;
    /** The most the kept patches may hold: their bytes and, while kept, the tiles they were made from. */
    private static final long MAX_RETAINED = 16L * 1024 * 1024;
    /** How often a connection's pipeline is looked at again on the sender's side (the write looks every time). */
    private static final long RECHECK_NANOS = 1_000_000_000L;
    private static final int MAX_PACKET = 8 * 1024 * 1024;
    /**
     * Patches only one player is sent are made here too (off the connections' threads), but only up to this
     * share of one core on average, in bursts of up to {@link #ALONE_BURST_NANOS}: the sender's one thread must
     * not fall behind where many players each watch their own screen, which the connections' many threads share
     * out between them. Past that they go as packets, as before.
     */
    private static final double ALONE_SHARE = 0.5;
    private static final long ALONE_BURST_NANOS = 20_000_000L;
    private static final String HACKFIX = "hackfix";

    private final Packets packets;
    private final Logger log;
    private final Class<?> compressClass, prependerClass, encoderClass, unbundlerClass, connectionClass;
    private final String compressName, prependerName, encoderName;
    private final Field protocolInfo;
    private final MethodHandle threshold, protocolId, protocolCodec, bundlerInfo, unbundle, encode, deflate, closeCompressor;
    private final Object play;
    /** The last game protocol seen and its delimiter: {protocol info, delimiter}. */
    private volatile Object[] delimiter = {null, null};
    /** Makes a compressor: the factory Paper's connections use, at their level. Each thread compressing has its own. */
    private final Object factory;
    private final MethodHandle createCompressor;
    private final int level;
    private final String compressorName;
    /** Every compressor made, to close: one per thread that has compressed (the sender's, the {@link Cpu} pool's). */
    private final List<Worker> workers = new ArrayList<>();
    private final ThreadLocal<Worker> worker = new ThreadLocal<>();

    // The rest only under this object's lock: the sender's thread, and close().
    private final LinkedHashMap<Key, Compressed> kept = new LinkedHashMap<>(256, 0.75f, true);
    private long retained;
    private final Map<Channel, Checked> checked = new WeakHashMap<>();
    private boolean closed;
    private final Set<String> said = new HashSet<>();
    private double aloneBudget = ALONE_BURST_NANOS;
    private long aloneAt = System.nanoTime();

    /**
     * Counters for benchmarks and the report: patches made, patches handed out again, bytes made, and patches
     * for one player left to their connection because the budget for those was spent.
     */
    long made, reused, madeBytes, leftAlone;

    private SharedCompression(Packets packets, Logger log, int level) throws ReflectiveOperationException {
        this.packets = packets;
        this.log = log;
        MethodHandles.Lookup l = MethodHandles.lookup();
        String net = "net.minecraft.network.";
        compressClass = Reflect.cls(net + "CompressionEncoder");
        prependerClass = Reflect.cls(net + "Varint21LengthFieldPrepender");
        encoderClass = Reflect.cls(net + "PacketEncoder");
        unbundlerClass = Reflect.cls(net + "PacketBundleUnpacker");
        connectionClass = Reflect.cls(net + "Connection");
        // HandlerNames' constants, inlined where the game uses them, so a server jar may not carry the class.
        compressName = "compress";
        prependerName = "prepender";
        encoderName = "encoder";
        protocolInfo = Reflect.field(encoderClass, "protocolInfo");
        protocolInfo.setAccessible(true);
        threshold = l.unreflect(Reflect.method(compressClass, "getThreshold"));
        Class<?> info = Reflect.cls(net + "ProtocolInfo");
        protocolId = l.unreflect(Reflect.method(info, "id"));
        protocolCodec = l.unreflect(Reflect.method(info, "codec"));
        play = Reflect.field(Reflect.cls(net + "ConnectionProtocol"), "PLAY").get(null);
        encode = l.unreflect(Reflect.method(Reflect.cls(net + "codec.StreamEncoder"), "encode", Object.class, Object.class));
        bundlerInfo = l.unreflect(Reflect.method(info, "bundlerInfo"));
        Class<?> bundler = Reflect.cls(net + "protocol.BundlerInfo");
        unbundle = l.unreflect(Reflect.method(bundler, "unbundlePacket", Reflect.cls(net + "protocol.Packet"), Consumer.class));
        // The compressor Paper's own connections use (libdeflate where Velocity has it for the platform, else
        // java.util.zip), at the server's level, so a patch costs here what it cost there.
        Object loader = Reflect.field(Class.forName("com.velocitypowered.natives.util.Natives"), "compress").get(null);
        factory = Reflect.method(loader.getClass(), "get").invoke(loader);
        Class<?> compressorType = Class.forName("com.velocitypowered.natives.compression.VelocityCompressor");
        createCompressor = l.unreflect(Reflect.method(Class.forName("com.velocitypowered.natives.compression.VelocityCompressorFactory"), "create", int.class));
        this.level = level;
        compressorName = String.valueOf(Reflect.method(loader.getClass(), "getLoadedVariant").invoke(loader));
        deflate = l.unreflect(Reflect.method(compressorType, "deflate", ByteBuf.class, ByteBuf.class));
        closeCompressor = l.unreflect(Reflect.method(compressorType, "close"));
        // One now, so a server whose natives cannot make one says so at start, as before.
        worker();
    }

    /**
     * Shared compression for a Paper server, or null (said in one line) where this server's network classes
     * are not the ones it was written for.
     */
    public static SharedCompression create(Packets packets, Logger log) {
        try {
            SharedCompression s = new SharedCompression(packets, log, paperLevel());
            log.info("Map pictures are compressed once for everyone watching them (" + s.compressorName + ").");
            return s;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            log.info("Map pictures are compressed by each player's connection: this server's network classes are not "
                    + "the ones shared compression was written for (" + e + ").");
            return null;
        }
    }

    /** For tests and benchmarks: the compressor at [level] (-1 for its default), whatever server this is. */
    static SharedCompression create(Packets packets, Logger log, int level) throws ReflectiveOperationException {
        return new SharedCompression(packets, log, level);
    }

    /** Paper's {@code misc.compression-level}, which its connections compress at; -1 (the default) if unreadable. */
    private static int paperLevel() {
        try {
            Object config = Reflect.method(Class.forName("io.papermc.paper.configuration.GlobalConfiguration"), "get").invoke(null);
            Object misc = Reflect.field(config.getClass(), "misc").get(config);
            Object level = Reflect.field(misc.getClass(), "compressionLevel").get(misc);
            return (int) Reflect.method(level.getClass(), "or", int.class).invoke(level, -1);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    /**
     * The rectangle of [tile] for map [mapId] as [channel] can take it, compressed once for every connection
     * that asks for the same tile, rectangle and map; null where the connection cannot take it, or [shared] is
     * false (nobody else is sent it) and the budget for those is spent: then send a packet as before. Must not
     * be called on a connection's own thread: this is the work kept off it.
     */
    public Object patch(Channel channel, int mapId, int x, int y, int w, int h, byte[] tile, boolean shared) {
        return patches(channel, List.of(new Wire.MapPatch(mapId, x, y, w, h, tile, shared))).get(0);
    }

    /**
     * {@link #patch} for each of [list] (one pass's patches for one player), in order, null where that gives
     * null. Those not kept already are compressed at once on the {@link Cpu} pool's threads, each with a
     * compressor of its own: one at a time, a hover that changes 24 tiles took 10 to 16 ms on the sender's thread.
     */
    public List<Object> patches(Channel channel, List<Wire.MapPatch> list) {
        int n = list.size();
        Object[] out = new Object[n];
        long now = System.nanoTime();
        Route route;
        int[] todo = new int[n];
        int count = 0;
        synchronized (this) {
            if (closed) return java.util.Arrays.asList(out);
            route = cachedRoute(channel, now);
            if (route == null) return java.util.Arrays.asList(out);
            boolean refilled = false;
            for (int i = 0; i < n; i++) {
                Wire.MapPatch p = list.get(i);
                Compressed c = kept.get(key(p, route));
                if (c != null) {
                    reused++;
                    c.used = now;
                    out[i] = c;
                    continue;
                }
                if (!p.shared()) {
                    if (!refilled) {
                        aloneBudget = Math.min(ALONE_BURST_NANOS, aloneBudget + (now - aloneAt) * ALONE_SHARE);
                        aloneAt = now;
                        refilled = true;
                    }
                    if (aloneBudget <= 0) {
                        leftAlone++;
                        continue;
                    }
                }
                todo[count++] = i;
            }
        }
        if (count == 0) return java.util.Arrays.asList(out);
        Compressed[] done = new Compressed[n];
        long[] nanos = new long[n];
        int k = count;
        java.util.function.IntConsumer one = j -> {
            int i = todo[j];
            long start = System.nanoTime();
            done[i] = worker().compress(route, list.get(i));
            nanos[i] = System.nanoTime() - start;
        };
        // This thread takes patches one after another, and so does each pool thread that is free meanwhile: busy
        // painting a picture, the pool only means it is all done here, as if there were no pool.
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch left = new java.util.concurrent.CountDownLatch(k);
        Runnable take = () -> {
            for (int j; (j = next.getAndIncrement()) < k; ) {
                try {
                    one.accept(j);
                } finally {
                    left.countDown();
                }
            }
        };
        if (k > 1 && !(Thread.currentThread() instanceof java.util.concurrent.ForkJoinWorkerThread)) {
            java.util.concurrent.ForkJoinPool pool = Cpu.pool();
            for (int h = Math.min(k, pool.getParallelism()) - 1; h > 0; h--) pool.execute(take);
        }
        take.run();
        try {
            left.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            if (closed) return java.util.Arrays.asList(new Object[n]);
            for (int j = 0; j < k; j++) {
                int i = todo[j];
                Wire.MapPatch p = list.get(i);
                if (!p.shared()) aloneBudget -= nanos[i];
                Compressed c = done[i];
                if (c == null) continue;
                made++;
                madeBytes += c.frame.length;
                c.used = now;
                if (kept.put(key(p, route), c) == null) retained += cost(c);
                out[i] = c;
            }
            trim(now);
        }
        return java.util.Arrays.asList(out);
    }

    private static Key key(Wire.MapPatch p, Route route) {
        return new Key(p.tile(), p.x(), p.y(), p.w(), p.h(), p.mapId(), route.threshold);
    }

    /** This thread's compressor, made the first time it compresses. */
    private Worker worker() {
        Worker w = worker.get();
        if (w == null) {
            w = new Worker();
            synchronized (workers) {
                workers.add(w);
            }
            worker.set(w);
        }
        return w;
    }

    /** One thread's compressor and buffers. */
    private final class Worker {
        private final Object compressor;
        private final ByteBuf raw = Unpooled.directBuffer(32 * 1024);
        private final ByteBuf out = Unpooled.directBuffer(32 * 1024);
        private boolean closed;

        Worker() {
            try {
                compressor = createCompressor.invoke(factory, level);
            } catch (Throwable t) {
                raw.release();
                out.release();
                throw Nms.rethrow(t);
            }
        }

        /** [p] as [route]'s connections pass it on, or null if too big for them or this compressor is closed. */
        synchronized Compressed compress(Route route, Wire.MapPatch p) {
            if (closed) return null;
            Object packet = packets.patch(p.mapId(), p.x(), p.y(), p.w(), p.h(), rectangle(p.tile(), p.x(), p.y(), p.w(), p.h()));
            raw.clear();
            out.clear();
            try {
                encode.invoke(route.codec, raw, packet);
                int length = raw.readableBytes();
                if (length > MAX_PACKET) return null; // the connection's own compression refuses it, loudly
                if (length < route.threshold) {
                    writeVarInt(out, 0);
                    out.writeBytes(raw);
                } else {
                    writeVarInt(out, length);
                    out.ensureWritable(length + 64);
                    deflate.invoke(compressor, raw, out);
                }
            } catch (Throwable t) {
                throw Nms.rethrow(t);
            }
            byte[] frame = new byte[out.readableBytes()];
            out.getBytes(out.readerIndex(), frame);
            return new Compressed(frame, route.threshold, p.mapId(), p.x(), p.y(), p.w(), p.h(), p.tile());
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            raw.release();
            out.release();
            try {
                closeCompressor.invoke(compressor);
            } catch (Throwable t) {
                throw Nms.rethrow(t);
            }
        }
    }

    /** Whether [packets] holds anything {@link #patch} made, which only {@link #write} can write. */
    public static boolean any(List<?> packets) {
        for (Object p : packets) if (p instanceof Compressed) return true;
        return false;
    }

    /**
     * Writes [packets] to [channel], [together] as one bundle; on the channel's own thread. The compressed ones
     * go in after the connection's compression where the pipeline is still as it was when they were made; the
     * rest, and all of them where it is not, as packets. The future of the last write.
     */
    public ChannelFuture write(Channel channel, List<Object> list, boolean together) {
        Route route = route(channel, false);
        ChannelHandlerContext ctx = route == null ? null : channel.pipeline().context(compressName);
        if (ctx == null) {
            // Packets as before: the connection changed since (left the game protocol, a handler was added).
            List<Object> objects = new ArrayList<>(list.size());
            for (Object p : list) objects.add(p instanceof Compressed c ? c.packet(packets) : p);
            if (together) return channel.write(packets.bundle(objects));
            ChannelFuture last = null;
            for (Object p : objects) last = channel.write(p);
            return last;
        }
        // A bundle is a delimiter, its packets and a delimiter on the wire: what the unbundler would write.
        ChannelFuture last = together ? channel.write(route.delimiter) : null;
        for (Object p : list) {
            if (!(p instanceof Compressed c)) last = channel.write(p);
            else if (c.threshold == route.threshold) last = ctx.write(Unpooled.wrappedBuffer(c.frame).asReadOnly());
            else last = channel.write(c.packet(packets)); // the threshold changed since it was made
        }
        if (together) last = channel.write(route.delimiter);
        return last;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            kept.clear();
            checked.clear();
        }
        // A compression running on another thread finishes first (each compressor is closed under its own lock).
        synchronized (workers) {
            for (Worker w : workers) w.close();
            workers.clear();
        }
    }

    private Route cachedRoute(Channel channel, long now) {
        Checked c = checked.get(channel);
        if (c == null || now - c.at > RECHECK_NANOS) {
            c = new Checked(route(channel, true), now);
            checked.put(channel, c);
        }
        return c.route;
    }

    /**
     * How [channel] can take compressed patches, or null if it cannot: see the class comment. Reads the pipeline
     * only, so it can run on any thread; [say] tells the log, once for each reason, why a connection cannot.
     */
    private Route route(Channel channel, boolean say) {
        if (!channel.getClass().getName().startsWith("io.netty.")) {
            return refuse(say, "its channel is a " + channel.getClass().getName() + " (a plugin wraps the connection)");
        }
        ChannelPipeline p = channel.pipeline();
        List<String> names = p.names();
        int at = names.indexOf(compressName);
        ChannelHandler compress = at < 0 ? null : p.get(compressName);
        if (compress == null) return refuse(say, "it is not compressed (network-compression-threshold is -1)");
        if (compress.getClass() != compressClass) return refuse(say, "its compression is " + compress.getClass().getName());
        ChannelHandler prepender = p.get(prependerName);
        if (at == 0 || !prependerName.equals(names.get(at - 1)) || prepender == null || prepender.getClass() != prependerClass) {
            return refuse(say, "something sits between its compression and its framing");
        }
        Object encoder = null;
        for (int i = at + 1; i < names.size(); i++) {
            String name = names.get(i);
            ChannelHandler h = p.get(name);
            if (h == null || !(h instanceof ChannelOutboundHandler)) continue; // gone, or never sees a write
            Class<?> k = h.getClass();
            if (k == encoderClass && name.equals(encoderName)) encoder = h;
            else if (k == unbundlerClass || k == connectionClass || h instanceof LeavesMapsAlone) continue;
            else if (name.equals(HACKFIX) && k.getName().startsWith(connectionClass.getName() + "$")) continue;
            else return refuse(say, "handler '" + name + "' (" + k.getName() + ") sees its packets on their way out and would not see map pictures");
        }
        if (encoder == null) return null; // not in a protocol yet, or switching (configuration): for a moment
        try {
            Object info = protocolInfo.get(encoder);
            if (protocolId.invoke(info) != play) return null;
            Object delimiter = delimiter(info);
            return delimiter == null ? null : new Route((int) threshold.invoke(compress), protocolCodec.invoke(info), delimiter);
        } catch (Throwable t) {
            throw Nms.rethrow(t);
        }
    }

    /**
     * The delimiter [info]'s bundles go between, or null if it has none: what its own unbundler writes around an
     * empty bundle. Its encoder takes that one instance only.
     */
    private Object delimiter(Object info) throws Throwable {
        Object[] last = delimiter;
        if (last[0] == info) return last[1];
        Object bundler = bundlerInfo.invoke(info);
        if (bundler == null) return null;
        Object[] got = new Object[1];
        Consumer<Object> first = p -> {
            if (got[0] == null) got[0] = p;
        };
        unbundle.invoke(bundler, packets.bundle(List.of()), first);
        delimiter = new Object[]{info, got[0]};
        return got[0];
    }

    private Route refuse(boolean say, String why) {
        if (say) {
            boolean first;
            synchronized (said) {
                first = said.size() < 64 && said.add(why);
            }
            if (first) log.info("Map pictures are compressed by the player's own connection where " + why + ".");
        }
        return null;
    }

    /** Drops the patches nobody asked for lately, and the oldest while over the limit. */
    private void trim(long now) {
        for (Iterator<Compressed> it = kept.values().iterator(); it.hasNext(); ) {
            Compressed c = it.next();
            if (retained <= MAX_RETAINED && now - c.used <= IDLE_NANOS) break; // the rest were used since
            it.remove();
            retained -= cost(c);
        }
    }

    /** What keeping [c] holds on to: its bytes, and its tile (which may otherwise be garbage by now). */
    private static long cost(Compressed c) {
        return c.frame.length + c.tile.length + 64L;
    }

    /** Patches kept now. */
    synchronized int kept() {
        return kept.size();
    }

    static byte[] rectangle(byte[] tile, int x, int y, int w, int h) {
        byte[] data = new byte[w * h];
        for (int row = 0; row < h; row++) System.arraycopy(tile, (y + row) * 128 + x, data, row * w, w);
        return data;
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
