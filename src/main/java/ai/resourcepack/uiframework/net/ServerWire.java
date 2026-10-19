package ai.resourcepack.uiframework.net;

import ai.resourcepack.uiframework.world.MapWall;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/** The {@link Wire} of a running server: packets by {@link Nms}, frame pointing kept in each connection by {@link Pipeline}. */
public final class ServerWire implements Wire {
    private final Nms nms;
    private final Pipeline pipeline;

    public ServerWire(Nms nms, Pipeline pipeline) {
        this.nms = nms;
        this.pipeline = pipeline;
    }

    @Override
    public Object patch(int mapId, int x, int y, int w, int h, byte[] colors) {
        return nms.mapPatch(mapId, x, y, w, h, colors);
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
        return nms.writeNow(player, together ? List.of(nms.bundle(packets)) : packets, done);
    }
}
