package ai.resourcepack.ui.framework.net;

import ai.resourcepack.ui.framework.world.MapWall;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/**
 * What {@link MapSender} writes through: players' connections on a server ({@link ServerWire}), or a
 * recording in a test. Packets are opaque to the sender: it only makes them here and writes them here.
 */
public interface Wire {
    /** A packet that brings the [w] × [h] rectangle at (x, y) of map [mapId] to [colors]. */
    Object patch(int mapId, int x, int y, int w, int h, byte[] colors);

    /**
     * A packet that brings the [w] × [h] rectangle at (x, y) of map [mapId] to that rectangle of [tile] (128 × 128,
     * never changed once published), for [player]; [shared]: other players are sent the same tile now too. A wire
     * may hand several players the same object for the same tile, rectangle and map; by default this copies the
     * rectangle out for {@link #patch(int, int, int, int, int, byte[])}.
     */
    default Object patch(Player player, int mapId, int x, int y, int w, int h, byte[] tile, boolean shared) {
        return patch(mapId, x, y, w, h, SharedCompression.rectangle(tile, x, y, w, h));
    }

    /** One rectangle of one tile for one map: what {@link #patch(Player, int, int, int, int, int, byte[], boolean)} takes. */
    record MapPatch(int mapId, int x, int y, int w, int h, byte[] tile, boolean shared) {}

    /**
     * {@link #patch(Player, int, int, int, int, int, byte[], boolean)} for each of [patches], in order: one pass's
     * worth for one player, which a wire may make on several threads at once.
     */
    default List<Object> patches(Player player, List<MapPatch> patches) {
        List<Object> out = new java.util.ArrayList<>(patches.size());
        for (MapPatch p : patches) out.add(patch(player, p.mapId(), p.x(), p.y(), p.w(), p.h(), p.tile(), p.shared()));
        return out;
    }

    /**
     * The bytes [packet] (made by {@link #patches}) takes on the wire, compressed, if the wire knows that before
     * writing it; -1 if not (it is compressed later, by the connection).
     */
    default long wireBytes(Object packet) {
        return -1;
    }

    /** A packet that makes [wall]'s frame for [tile] show map [mapId], or null while the frame is not in the world. */
    Object repoint(MapWall wall, int tile, int mapId);

    /**
     * Which map each frame shows on [player]'s client, for as long as this connection lasts; null until it
     * can be had (then nothing is re-pointed for them yet).
     */
    Frames frames(Player player);

    /**
     * Writes [packets] to [player] in order with one flush, from any thread; [together]: as one bundle, which
     * the client applies whole between two frames. [done] hears whether the last packet was written. False,
     * and [done] never called, if the player has no connection.
     */
    boolean write(Player player, List<Object> packets, boolean together, Consumer<Boolean> done);

    /** One client's frames: the map each shows, where the sender has pointed it at another of its wall's maps. */
    interface Frames {
        /** The map id each of [wall]'s tiles shows, or null if each shows its own ({@link MapWall#mapId}). */
        int[] pointing(MapWall wall);

        /**
         * [wall]'s tiles show [pointing] from now on: a frame this client is sent afresh (spawned, shown again)
         * is made to show it too. Called before the packets that re-point them are written.
         */
        void point(MapWall wall, int[] pointing);
    }
}
