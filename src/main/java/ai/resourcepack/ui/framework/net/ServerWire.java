package ai.resourcepack.ui.framework.net;

import ai.resourcepack.ui.framework.world.MapWall;
import io.netty.channel.Channel;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/**
 * The {@link Wire} of a running server: packets by {@link Nms}, frame pointing kept in each connection by {@link Pipeline},
 * and, where [shared] is not null, map patches compressed once for every connection that can take them ({@link SharedCompression}).
 */
public final class ServerWire implements Wire, AutoCloseable {
    private final Nms nms;
    private final Pipeline pipeline;
    private final SharedCompression shared;

    public ServerWire(Nms nms, Pipeline pipeline, SharedCompression shared) {
        this.nms = nms;
        this.pipeline = pipeline;
        this.shared = shared;
    }

    /** The packets {@link SharedCompression} writes where a connection cannot take its bytes. */
    public static SharedCompression.Packets packets(Nms nms) {
        return new SharedCompression.Packets() {
            @Override
            public Object patch(int mapId, int x, int y, int w, int h, byte[] colors) {
                return nms.mapPatch(mapId, x, y, w, h, colors);
            }

            @Override
            public Object bundle(List<?> packets) {
                return nms.bundle(packets);
            }
        };
    }

    @Override
    public Object patch(int mapId, int x, int y, int w, int h, byte[] colors) {
        return nms.mapPatch(mapId, x, y, w, h, colors);
    }

    @Override
    public Object patch(Player player, int mapId, int x, int y, int w, int h, byte[] tile, boolean others) {
        if (shared != null) {
            Channel channel = nms.channel(player);
            Object compressed = channel == null ? null : shared.patch(channel, mapId, x, y, w, h, tile, others);
            if (compressed != null) return compressed;
        }
        return Wire.super.patch(player, mapId, x, y, w, h, tile, others);
    }

    @Override
    public List<Object> patches(Player player, List<MapPatch> patches) {
        Channel channel = shared == null ? null : nms.channel(player);
        if (channel == null) return Wire.super.patches(player, patches);
        List<Object> out = shared.patches(channel, patches);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) != null) continue;
            MapPatch p = patches.get(i);
            out.set(i, Wire.super.patch(player, p.mapId(), p.x(), p.y(), p.w(), p.h(), p.tile(), p.shared()));
        }
        return out;
    }

    @Override
    public long wireBytes(Object packet) {
        return packet instanceof SharedCompression.Compressed c ? c.frame.length : -1;
    }

    @Override
    public Object repoint(MapWall wall, int tile, int mapId) {
        int entity = wall.entityId(tile);
        return entity < 0 ? null : nms.frameShows(entity, mapId);
    }

    @Override
    public Frames frames(Player player) {
        return pipeline.frames(player);
    }

    @Override
    public boolean write(Player player, List<Object> packets, boolean together, Consumer<Boolean> done) {
        if (shared != null && SharedCompression.any(packets)) return nms.writeNow(player, channel -> shared.write(channel, packets, together), done);
        return nms.writeNow(player, together ? List.of(nms.bundle(packets)) : packets, done);
    }

    @Override
    public void close() {
        if (shared != null) shared.close();
    }
}
