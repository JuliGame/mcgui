package ai.resourcepack.ui.framework.net;

import com.velocitypowered.natives.util.Natives;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.flush.FlushConsolidationHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.CipherDecoder;
import net.minecraft.network.CipherEncoder;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketBundlePacker;
import net.minecraft.network.PacketBundleUnpacker;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Connections built the way Paper 26.2 builds them, from its own classes: what a player's channel looks like
 * once in the game protocol, and a client's decoding side to read what it wrote.
 */
final class PaperNet {
    static final ProtocolInfo<ClientGamePacketListener> GAME;

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        try {
            // Paper's own defaults, which its Connection reads as it loads (a server reads them from paper-global.yml).
            var set = io.papermc.paper.configuration.GlobalConfiguration.class.getDeclaredMethod("set", io.papermc.paper.configuration.GlobalConfiguration.class);
            set.setAccessible(true);
            set.invoke(null, defaults(new io.papermc.paper.configuration.GlobalConfiguration()));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
        GAME = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY));
    }

    /** [part] with each of its sections made with their defaults, as loading an empty file makes them. */
    private static Object defaults(Object part) throws ReflectiveOperationException {
        for (var f : part.getClass().getFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.get(part) != null) continue;
            Class<?> type = f.getType();
            if (type.getEnclosingClass() == null || !io.papermc.paper.configuration.ConfigurationPart.class.isAssignableFrom(type)) continue;
            var make = type.getDeclaredConstructors()[0];
            make.setAccessible(true);
            try {
                f.set(part, defaults(make.getParameterCount() == 1 ? make.newInstance(part) : make.newInstance()));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                // A section that needs a running server to be made; nothing here reads it.
            }
        }
        return part;
    }

    private PaperNet() {}

    /** The packets the framework sends, made as {@link Nms#mapPatch} and {@link Nms#bundle} make them. */
    static final SharedCompression.Packets PACKETS = new SharedCompression.Packets() {
        @Override
        public Object patch(int mapId, int x, int y, int w, int h, byte[] colors) {
            return new ClientboundMapItemDataPacket(new MapId(mapId), (byte) 0, true, Optional.empty(),
                    Optional.of(new MapItemSavedData.MapPatch(x, y, w, h, colors)));
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Object bundle(List<?> packets) {
            return new ClientboundBundlePacket((Iterable) packets);
        }
    };

    static SecretKey key(int seed) {
        byte[] k = new byte[16];
        for (int i = 0; i < k.length; i++) k[i] = (byte) (seed * 31 + i * 7);
        return new SecretKeySpec(k, "AES");
    }

    /**
     * A player's channel as Paper has it in the game protocol: ServerConnectionListener's initChannel, then
     * Connection's setupCompression (if [threshold] is not -1), setEncryptionKey (if [key]) and
     * setupOutboundProtocol, each adding the handlers the server's own code adds, under the same names.
     */
    static EmbeddedChannel server(int threshold, SecretKey key) throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addFirst(new FlushConsolidationHandler());
        ch.pipeline().addLast("timeout", new ReadTimeoutHandler(30));
        Connection.configureSerialization(ch.pipeline(), PacketFlow.SERVERBOUND, false, null);
        new Connection(PacketFlow.SERVERBOUND).configurePacketHandler(ch.pipeline());
        if (key != null) ch.pipeline().addBefore("prepender", "encrypt", new CipherEncoder(Natives.cipher.get().forEncryption(key)));
        if (threshold >= 0) ch.pipeline().addAfter("prepender", "compress", new CompressionEncoder(Natives.compress.get().create(-1), threshold));
        UnconfiguredPipelineHandler.OutboundConfigurationTask task = UnconfiguredPipelineHandler.setupOutboundProtocol(GAME);
        PacketBundleUnpacker unbundler = new PacketBundleUnpacker(GAME.bundlerInfo());
        ch.writeAndFlush(task.andThen(ctx -> ctx.pipeline().addAfter("encoder", "unbundler", unbundler))).sync();
        return ch;
    }

    /** A handler of the kind a packet-listening plugin puts in, here [name] before [before]. */
    static void addBefore(EmbeddedChannel ch, String before, String name, ChannelHandler h) {
        ch.pipeline().addBefore(before, name, h);
    }

    /** The client's side of the same connection, as its Connection sets it up for the game protocol. */
    static EmbeddedChannel client(int threshold, SecretKey key) throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel();
        if (key != null) ch.pipeline().addLast("decrypt", new CipherDecoder(Natives.cipher.get().forDecryption(key)));
        ch.pipeline().addLast("splitter", new Varint21FrameDecoder(null));
        if (threshold >= 0) ch.pipeline().addLast("decompress", new CompressionDecoder(Natives.compress.get().create(-1), threshold, true));
        ch.pipeline().addLast("decoder", new PacketDecoder<>(GAME));
        ch.pipeline().addLast("bundler", new PacketBundlePacker(GAME.bundlerInfo()));
        return ch;
    }

    /** Every byte [ch] has written to its socket so far, in order. */
    static byte[] wire(EmbeddedChannel ch) {
        ch.flushOutbound();
        ByteBuf all = Unpooled.buffer();
        for (Object o; (o = ch.readOutbound()) != null; ) {
            ByteBuf b = (ByteBuf) o;
            all.writeBytes(b);
            b.release();
        }
        byte[] out = new byte[all.readableBytes()];
        all.readBytes(out);
        return out;
    }

    /** Drops what [ch] has written, releasing it. */
    static long drain(EmbeddedChannel ch) {
        long n = 0;
        for (Object o; (o = ch.readOutbound()) != null; ) {
            ByteBuf b = (ByteBuf) o;
            n += b.readableBytes();
            b.release();
        }
        return n;
    }

    /** What a client makes of [bytes]: the packets its decoder hands its listener. */
    static List<Object> decode(EmbeddedChannel client, byte[] bytes) {
        client.writeInbound(Unpooled.wrappedBuffer(bytes));
        List<Object> out = new ArrayList<>();
        for (Object o; (o = client.readInbound()) != null; ) out.add(o);
        return out;
    }
}
