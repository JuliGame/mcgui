package ai.resourcepack.ui.framework.net;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a client receives from a map patch compressed once and written past the connection's own encoder and
 * compression is what it received before: on Paper's own handlers, byte for byte, and decoded by the game's own
 * decoder into the same packet, field for field.
 */
class SharedCompressionTest {
    private static final int THRESHOLD = 256;
    private static final Logger LOG = Logger.getLogger("SharedCompressionTest");

    /** A patch to send: map id and rectangle of a tile. */
    private record Patch(int mapId, int x, int y, int w, int h) {}

    private static final List<Patch> PATCHES = List.of(
            new Patch(2_000_000_123, 0, 0, 128, 128), // a whole tile, at an id that takes five bytes
            new Patch(7, 5, 7, 60, 30),
            new Patch(2_000_000_124, 3, 4, 4, 4), // under the threshold: sent as it is
            new Patch(1, 127, 127, 1, 1),
            new Patch(0, 0, 0, 128, 1));

    /** A tile with the runs and edges of a drawn page and some noise, so it compresses like one. */
    static byte[] tile(long seed) {
        Random r = new Random(seed);
        byte[] t = new byte[128 * 128];
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                int v = (x / 16 + y / 8) % 5 == 0 ? 34 : 119;
                if (r.nextInt(10) == 0) v = 4 + r.nextInt(240);
                t[y * 128 + x] = (byte) v;
            }
        }
        return t;
    }

    private static SharedCompression shared() throws Exception {
        PaperNet.GAME.id(); // the game's bootstrap, before the reflection looks
        return SharedCompression.create(PaperNet.PACKETS, LOG, -1);
    }

    /** [patches] of [tile] as packet objects, as the sender makes them without shared compression. */
    private static List<Object> objects(byte[] tile, List<Patch> patches) {
        List<Object> out = new ArrayList<>();
        for (Patch p : patches) out.add(PaperNet.PACKETS.patch(p.mapId, p.x, p.y, p.w, p.h, SharedCompression.rectangle(tile, p.x, p.y, p.w, p.h)));
        return out;
    }

    private static List<Object> compressed(SharedCompression s, EmbeddedChannel ch, byte[] tile, List<Patch> patches) {
        List<Object> out = new ArrayList<>();
        for (Patch p : patches) {
            Object c = s.patch(ch, p.mapId, p.x, p.y, p.w, p.h, tile, true);
            assertInstanceOf(SharedCompression.Compressed.class, c, "a plain Paper connection takes compressed patches");
            out.add(c);
        }
        return out;
    }

    /** The old path: packet objects into the channel, a bundle as one bundle packet. */
    private static byte[] before(EmbeddedChannel ch, List<Object> packets, boolean together) {
        if (together) ch.write(PaperNet.PACKETS.bundle(packets));
        else for (Object p : packets) ch.write(p);
        ch.flush();
        return PaperNet.wire(ch);
    }

    private static byte[] after(SharedCompression s, EmbeddedChannel ch, List<Object> packets, boolean together) {
        s.write(ch, packets, together);
        ch.flush();
        return PaperNet.wire(ch);
    }

    private static void assertSamePatch(Patch want, byte[] tile, Object got) {
        ClientboundMapItemDataPacket p = assertInstanceOf(ClientboundMapItemDataPacket.class, got);
        assertEquals(want.mapId, p.mapId().id());
        assertEquals(0, p.scale());
        assertTrue(p.locked());
        assertTrue(p.decorations().isEmpty());
        MapItemSavedData.MapPatch patch = p.colorPatch().orElseThrow();
        assertEquals(want.x, patch.startX());
        assertEquals(want.y, patch.startY());
        assertEquals(want.w, patch.width());
        assertEquals(want.h, patch.height());
        assertArrayEquals(SharedCompression.rectangle(tile, want.x, want.y, want.w, want.h), patch.mapColors());
    }

    @Test
    void patchesReachTheClientByteForByteAsBefore() throws Exception {
        for (int threshold : new int[]{THRESHOLD, 0, 64, 1024 * 32}) {
            for (SecretKey key : new SecretKey[]{null, PaperNet.key(3)}) {
                try (SharedCompression s = shared()) {
                    byte[] tile = tile(threshold + 1);
                    EmbeddedChannel a = PaperNet.server(threshold, key), b = PaperNet.server(threshold, key);
                    byte[] old = before(a, objects(tile, PATCHES), false);
                    byte[] now = after(s, b, compressed(s, b, tile, PATCHES), false);
                    assertArrayEquals(old, now, "threshold " + threshold + ", encrypted " + (key != null));
                    List<Object> got = PaperNet.decode(PaperNet.client(threshold, key), now);
                    assertEquals(PATCHES.size(), got.size());
                    for (int i = 0; i < PATCHES.size(); i++) assertSamePatch(PATCHES.get(i), tile, got.get(i));
                }
            }
        }
    }

    @Test
    void aBundleIsTheSameBundle() throws Exception {
        try (SharedCompression s = shared()) {
            SecretKey key = PaperNet.key(9);
            byte[] tile = tile(42);
            EmbeddedChannel a = PaperNet.server(THRESHOLD, key), b = PaperNet.server(THRESHOLD, key);
            // Stand-ins for the frames' re-pointing, which go in the same bundle as packet objects.
            List<Object> old = new ArrayList<>(List.of(new ClientboundPingPacket(1)));
            old.addAll(objects(tile, PATCHES.subList(0, 2)));
            old.add(new ClientboundPingPacket(2));
            List<Object> now = new ArrayList<>(List.of(new ClientboundPingPacket(1)));
            now.addAll(compressed(s, b, tile, PATCHES.subList(0, 2)));
            now.add(new ClientboundPingPacket(2));
            byte[] x = before(a, old, true), y = after(s, b, now, true);
            assertArrayEquals(x, y);
            List<Object> got = PaperNet.decode(PaperNet.client(THRESHOLD, key), y);
            assertEquals(1, got.size());
            List<Packet<?>> subs = new ArrayList<>();
            assertInstanceOf(ClientboundBundlePacket.class, got.getFirst()).subPackets().forEach(subs::add);
            assertEquals(4, subs.size());
            assertEquals(1, assertInstanceOf(ClientboundPingPacket.class, subs.get(0)).getId());
            assertSamePatch(PATCHES.get(0), tile, subs.get(1));
            assertSamePatch(PATCHES.get(1), tile, subs.get(2));
            assertEquals(2, assertInstanceOf(ClientboundPingPacket.class, subs.get(3)).getId());
        }
    }

    @Test
    void onePatchIsMadeOnceForEveryConnection() throws Exception {
        try (SharedCompression s = shared()) {
            byte[] tile = tile(5);
            EmbeddedChannel a = PaperNet.server(THRESHOLD, PaperNet.key(1)), b = PaperNet.server(THRESHOLD, PaperNet.key(2));
            Object x = s.patch(a, 10, 0, 0, 128, 128, tile, true);
            assertSame(x, s.patch(b, 10, 0, 0, 128, 128, tile, true));
            assertEquals(1, s.made);
            // The same colours in another array, another rectangle, another map, another threshold: made anew.
            assertFalse(x == s.patch(a, 10, 0, 0, 128, 128, tile.clone(), true));
            assertFalse(x == s.patch(a, 10, 0, 0, 128, 127, tile, true));
            assertFalse(x == s.patch(a, 11, 0, 0, 128, 128, tile, true));
            assertFalse(x == s.patch(PaperNet.server(64, null), 10, 0, 0, 128, 128, tile, true));
            assertEquals(5, s.made);
        }
    }

    @Test
    void patchesForOnePlayerStayWithinTheirShareOfTheSendersThread() throws Exception {
        try (SharedCompression s = shared()) {
            EmbeddedChannel ch = PaperNet.server(THRESHOLD, null);
            byte[] tile = tile(3);
            byte[][] next = new byte[20][];
            for (int i = 0; i < next.length; i++) next[i] = tile.clone();
            int given = 0;
            for (int i = 0; i < 3000 && s.leftAlone == 0; i++) if (s.patch(ch, 1, 0, 0, 128, 128, tile.clone(), false) != null) given++;
            // Straight after, before the assertions (building their messages takes time too). The budget earns half of
            // all the time that passes, so a call just after the one that ran it out can earn a patch now and then, as
            // the single call this test used to make did on main too. Twenty in a row cannot earn many.
            int again = 0;
            for (byte[] t : next) if (s.patch(ch, 1, 0, 0, 128, 128, t, false) != null) again++;
            assertTrue(given > 0, "a burst is made here");
            assertTrue(s.leftAlone > 0, "then they are left to the connection, " + given + " made");
            assertTrue(again <= 4, "and the next ones too, but for one now and then: no time to earn more, " + again + " of 20 made");
            // Patches others are sent too are made whatever the budget.
            assertNotNull(s.patch(ch, 1, 0, 0, 128, 128, tile.clone(), true));
        }
    }

    @Test
    void ourOwnHandlersAndInboundOnesLeaveItOn() throws Exception {
        try (SharedCompression s = shared()) {
            EmbeddedChannel ch = PaperNet.server(THRESHOLD, null);
            class Ours extends ChannelDuplexHandler implements SharedCompression.LeavesMapsAlone {}
            ch.pipeline().addBefore("packet_handler", "uiengine_frames", new Ours());
            ch.pipeline().addAfter("decoder", "someone_reading", new ChannelInboundHandlerAdapter());
            assertNotNull(s.patch(ch, 1, 0, 0, 128, 128, tile(1), true));
        }
    }

    @Test
    void anythingElseThatSeesPacketsGoingOutTurnsItOff() throws Exception {
        byte[] tile = tile(77);
        // Where ViaVersion and PacketEvents put theirs, beside the encoder, and where a plugin adds one at the end.
        for (String[] at : new String[][]{{"encoder", "via-encoder"}, {"unbundler", "pe-encoder"}, {null, "listener"}}) {
            try (SharedCompression s = shared()) {
                EmbeddedChannel clean = PaperNet.server(THRESHOLD, null);
                List<Object> made = compressed(s, clean, tile, PATCHES);
                EmbeddedChannel ch = PaperNet.server(THRESHOLD, null), ref = PaperNet.server(THRESHOLD, null);
                ChannelOutboundHandlerAdapter foreign = new ChannelOutboundHandlerAdapter();
                if (at[0] == null) ch.pipeline().addLast(at[1], foreign);
                else ch.pipeline().addBefore(at[0], at[1], foreign);
                assertNull(s.patch(ch, 1, 0, 0, 128, 128, tile, true), at[1]);
                // Patches made before the handler came still reach it, as packets: the same bytes as before.
                assertArrayEquals(before(ref, objects(tile, PATCHES), true), after(s, ch, made, true), at[1]);
            }
        }
    }

    @Test
    void noCompressionOrNoGameProtocolTurnsItOff() throws Exception {
        try (SharedCompression s = shared()) {
            assertNull(s.patch(PaperNet.server(-1, null), 1, 0, 0, 128, 128, tile(1), true));
            // Still logging in or configuring: no game encoder.
            EmbeddedChannel config = new EmbeddedChannel();
            net.minecraft.network.Connection.configureSerialization(config.pipeline(), net.minecraft.network.protocol.PacketFlow.SERVERBOUND, false, null);
            config.pipeline().addAfter("prepender", "compress", new net.minecraft.network.CompressionEncoder(THRESHOLD));
            assertNull(s.patch(config, 1, 0, 0, 128, 128, tile(1), true));
        }
    }

    @Test
    void aChangedThresholdGetsPacketsAgain() throws Exception {
        try (SharedCompression s = shared()) {
            byte[] tile = tile(8);
            List<Object> made = compressed(s, PaperNet.server(THRESHOLD, null), tile, PATCHES);
            EmbeddedChannel a = PaperNet.server(64, null), b = PaperNet.server(64, null);
            byte[] now = after(s, b, made, false);
            assertArrayEquals(before(a, objects(tile, PATCHES), false), now);
            List<Object> got = PaperNet.decode(PaperNet.client(64, null), now);
            for (int i = 0; i < PATCHES.size(); i++) assertSamePatch(PATCHES.get(i), tile, got.get(i));
        }
    }
}
