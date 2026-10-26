package ai.resourcepack.ui.framework.net;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.render.Palette;
import ai.resourcepack.ui.framework.world.MapWall;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.world.level.material.MapColor;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * CPU and allocation of one full-screen change (a 16x9 wall, every tile new) for 1, 10 and 50 players: the
 * sender's thread (diff, patches, and with shared compression the encoding and deflating) and the connections'
 * threads (Paper's own encoder, compression, framing and encryption on each player's channel), before and
 * after, interleaved. Players watch one shared canvas ("shared"), or each their own canvas with the same
 * picture ("private"), where nothing is shared and the deflating only moves to the sender's thread.
 *
 * <p>Args: [changes per run] [reps] [samples dir]. Prints one line per run and a summary table.
 */
public final class SharedCompressionBench {
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final int COLS = 16, ROWS = 9;

    private record Write(Player player, List<Object> packets, boolean together, Consumer<Boolean> done) {}

    /** The connections of a running server, each a real Paper pipeline, written on this thread when told. */
    private static final class BenchWire implements Wire {
        final SharedCompression shared;
        final Map<Player, EmbeddedChannel> channels = new IdentityHashMap<>();
        final Map<Player, MessageDigest> digests = new IdentityHashMap<>();
        final List<Write> queued = new ArrayList<>();
        long wireBytes;

        BenchWire(SharedCompression shared) {
            this.shared = shared;
        }

        @Override
        public Object patch(int mapId, int x, int y, int w, int h, byte[] colors) {
            return PaperNet.PACKETS.patch(mapId, x, y, w, h, colors);
        }

        @Override
        public Object patch(Player player, int mapId, int x, int y, int w, int h, byte[] tile, boolean others) {
            if (shared != null) {
                Object c = shared.patch(channels.get(player), mapId, x, y, w, h, tile, others);
                if (c != null) return c;
            }
            return Wire.super.patch(player, mapId, x, y, w, h, tile, others);
        }

        @Override
        public Object repoint(MapWall wall, int tile, int mapId) {
            return null;
        }

        @Override
        public Frames frames(Player player) {
            return null;
        }

        @Override
        public boolean write(Player player, List<Object> packets, boolean together, Consumer<Boolean> done) {
            queued.add(new Write(player, packets, together, done));
            return true;
        }

        /** Written and not yet looked at: what each connection put on its socket. */
        final List<Map.Entry<Player, ByteBuf>> out = new ArrayList<>();

        /** What each connection's thread does with the queued writes: ServerWire's write, then the flush. */
        void deliver() {
            for (Write w : queued) {
                EmbeddedChannel ch = channels.get(w.player);
                if (shared != null && SharedCompression.any(w.packets)) shared.write(ch, w.packets, w.together);
                else if (w.together) ch.write(PaperNet.PACKETS.bundle(w.packets));
                else for (Object p : w.packets) ch.write(p);
                ch.flush();
                for (Object o; (o = ch.readOutbound()) != null; ) out.add(Map.entry(w.player, (ByteBuf) o));
                w.done.accept(true);
            }
            queued.clear();
        }

        /** Counts and digests what was written, outside the measured part. */
        void settle() {
            for (Map.Entry<Player, ByteBuf> e : out) {
                ByteBuf b = e.getValue();
                wireBytes += b.readableBytes();
                digests.get(e.getKey()).update(b.nioBuffer());
                b.release();
            }
            out.clear();
        }
    }

    private static Player player(int i) {
        UUID id = new UUID(0x5348, i);
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (self, m, args) -> switch (m.getName()) {
            case "getUniqueId" -> id;
            case "hashCode" -> id.hashCode();
            case "equals" -> self == args[0];
            case "toString" -> "player " + i;
            default -> null;
        });
    }

    /** Per change: sender and connection CPU (ns) and allocation (bytes), bytes on the wire, GC count and ms. */
    private record Result(double senderNs, double nettyNs, double senderAlloc, double nettyAlloc, double wire, long gcs, long gcMs,
                          String digest, long made, long reused, long leftAlone) {}

    private static long gcCount() {
        long n = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) n += b.getCollectionCount();
        return n;
    }

    private static long gcMs() {
        long n = 0;
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) n += b.getCollectionTime();
        return n;
    }

    private static Result run(boolean after, boolean sharedWall, int viewers, int changes, Palette palette, BufferedImage[] pictures) throws Exception {
        SharedCompression shared = after ? SharedCompression.create(PaperNet.PACKETS, Logger.getAnonymousLogger(), -1) : null;
        BenchWire wire = new BenchWire(shared);
        MapWall wall = new MapWall(null, 0, 100, 0, BlockFace.NORTH, COLS, ROWS, 7000);
        // 100 MB/s: every change goes in one pass (a burst is 25 MB), and a slow draw between passes cannot
        // overflow the sender's refill (bytes per second times nanoseconds), as Integer.MAX_VALUE does past 4 s.
        MapSender sender = new MapSender(wire, 100_000_000, Logger.getAnonymousLogger());
        List<Canvas> canvases = new ArrayList<>();
        List<MapSender.Assignment> assignments = new ArrayList<>();
        Canvas one = new Canvas(palette, COLS, ROWS);
        for (int i = 0; i < viewers; i++) {
            Player p = player(i);
            // Every player has their own key, as every online-mode connection does.
            EmbeddedChannel ch = PaperNet.server(256, PaperNet.key(i + 1));
            // An EmbeddedChannel's clock is the wall clock: a run longer than 30 s would time it out.
            ch.pipeline().remove("timeout");
            wire.channels.put(p, ch);
            wire.digests.put(p, MessageDigest.getInstance("SHA-1"));
            Canvas c = sharedWall ? one : new Canvas(palette, COLS, ROWS);
            if (!canvases.contains(c)) canvases.add(c);
            assignments.add(new MapSender.Assignment(p, wall, c, Canvas.Encoding.HI));
        }
        sender.update(assignments);
        long tid = Thread.currentThread().threadId();
        long senderNs = 0, nettyNs = 0, senderAlloc = 0, nettyAlloc = 0, wireBefore = 0;
        long gc0 = 0, gcMs0 = 0;
        int warm = 3;
        for (int k = 0; k < warm + changes; k++) {
            if (k == warm) {
                senderNs = nettyNs = senderAlloc = nettyAlloc = 0;
                wireBefore = wire.wireBytes;
                gc0 = gcCount();
                gcMs0 = gcMs();
                if (shared != null) shared.made = shared.reused = shared.leftAlone = 0;
            }
            BufferedImage picture = pictures[k % pictures.length];
            for (Canvas c : canvases) c.draw(picture, 0, 0);
            long c0 = THREADS.getCurrentThreadCpuTime(), a0 = THREADS.getThreadAllocatedBytes(tid);
            sender.pass();
            long c1 = THREADS.getCurrentThreadCpuTime(), a1 = THREADS.getThreadAllocatedBytes(tid);
            wire.deliver();
            long c2 = THREADS.getCurrentThreadCpuTime(), a2 = THREADS.getThreadAllocatedBytes(tid);
            senderNs += c1 - c0;
            senderAlloc += a1 - a0;
            nettyNs += c2 - c1;
            nettyAlloc += a2 - a1;
            wire.settle();
        }
        long gcs = gcCount() - gc0, gcTime = gcMs() - gcMs0;
        StringBuilder digest = new StringBuilder();
        MessageDigest all = MessageDigest.getInstance("SHA-1");
        for (int i = 0; i < viewers; i++) {
            for (Map.Entry<Player, MessageDigest> e : wire.digests.entrySet()) {
                if (e.getKey().getUniqueId().getLeastSignificantBits() == i) all.update(e.getValue().digest());
            }
        }
        for (byte b : all.digest()) digest.append(String.format("%02x", b));
        long made = shared == null ? 0 : shared.made, reused = shared == null ? 0 : shared.reused, left = shared == null ? 0 : shared.leftAlone;
        if (shared != null) shared.close();
        for (EmbeddedChannel ch : wire.channels.values()) ch.finishAndReleaseAll();
        return new Result((double) senderNs / changes, (double) nettyNs / changes, (double) senderAlloc / changes,
                (double) nettyAlloc / changes, (double) (wire.wireBytes - wireBefore) / changes, gcs, gcTime,
                digest.substring(0, 12), made / changes, reused / changes, left / changes);
    }

    public static void main(String[] args) throws Exception {
        int changes = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        int reps = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        File dir = new File(args.length > 2 ? args[2] : "build/samples");
        PaperNet.GAME.id();
        Palette palette = new Palette(MapColor::getColorFromPackedId);
        BufferedImage[] pictures = new BufferedImage[4];
        String[] names = {"landing@2x.png", "cards@2x.png", "menu@2x.png", "typography@2x.png"};
        for (int i = 0; i < names.length; i++) pictures[i] = ImageIO.read(new File(dir, names[i]));
        int[] counts = {1, 10, 50};
        String[] kinds = {"shared", "private"};
        // Warm both paths once, so the first measured runs are not the JIT's.
        run(false, true, 10, 4, palette, pictures);
        run(true, true, 10, 4, palette, pictures);
        Map<String, List<Result>> results = new HashMap<>();
        for (int rep = 0; rep < reps; rep++) {
            for (String kind : kinds) {
                for (int n : counts) {
                    for (boolean after : new boolean[]{rep % 2 == 1, rep % 2 == 0}) {
                        Result r = run(after, kind.equals("shared"), n, changes, palette, pictures);
                        String key = kind + " " + n + " " + (after ? "after" : "before");
                        results.computeIfAbsent(key, x -> new ArrayList<>()).add(r);
                        System.out.printf(Locale.ROOT, "rep %d %-20s sender %7.2f ms %8.1f MB | netty %7.2f ms %8.1f MB | wire %7.1f KB | gc %d (%d ms) | made %d reused %d left %d | %s%n",
                                rep, key, r.senderNs / 1e6, r.senderAlloc / 1e6, r.nettyNs / 1e6, r.nettyAlloc / 1e6, r.wire / 1024, r.gcs, r.gcMs,
                                r.made, r.reused, r.leftAlone, r.digest);
                    }
                }
            }
        }
        System.out.println();
        System.out.println("Per full-screen change (16x9 tiles, every tile new), median of " + reps + " runs of " + changes + " changes:");
        System.out.println("| players | wall | sender ms before | after | netty ms before | after | total ms before | after | alloc MB before | after | same bytes |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|---|");
        for (String kind : kinds) {
            for (int n : counts) {
                List<Result> b = results.get(kind + " " + n + " before"), a = results.get(kind + " " + n + " after");
                boolean same = true;
                for (int i = 0; i < b.size(); i++) same &= b.get(i).digest.equals(a.get(i).digest);
                System.out.printf(Locale.ROOT, "| %d | %s | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %.1f | %.1f | %s |%n", n, kind,
                        median(b, Result::senderNs) / 1e6, median(a, Result::senderNs) / 1e6,
                        median(b, Result::nettyNs) / 1e6, median(a, Result::nettyNs) / 1e6,
                        median(b, r -> r.senderNs + r.nettyNs) / 1e6, median(a, r -> r.senderNs + r.nettyNs) / 1e6,
                        median(b, r -> r.senderAlloc + r.nettyAlloc) / 1e6, median(a, r -> r.senderAlloc + r.nettyAlloc) / 1e6,
                        same ? "yes" : "NO");
            }
        }
    }

    private static double median(List<Result> rs, java.util.function.ToDoubleFunction<Result> f) {
        double[] v = rs.stream().mapToDouble(f).sorted().toArray();
        return v.length % 2 == 1 ? v[v.length / 2] : (v[v.length / 2 - 1] + v[v.length / 2]) / 2;
    }
}
