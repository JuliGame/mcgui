package ai.resourcepack.uiframework.net;

import ai.resourcepack.uiframework.world.MapWall;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelPipeline;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The netty handlers the engine puts in a player's connection: ping answers, one for a seated player that
 * hides their inventory, reads their mouse the moment it arrives, and moves their pointer, and one that
 * keeps which map each frame shows that player once the sender has pointed frames at other maps.
 */
public final class Pipeline {
    private static final String PONGS = "uiframework_pong";
    private static final String FILTER = "uiframework_conceal";
    private static final String FRAMES = "uiframework_frames";
    /** The player's own 36 slots, which every container other than the inventory itself ends with. */
    private static final int PLAYER_SLOTS = 36;

    private final Nms nms;
    /** Pings sent and not yet answered, with whose they are, so a player who leaves first takes theirs along. */
    private final Map<Integer, Pending> waiting = new ConcurrentHashMap<>();
    private final AtomicInteger pingIds = new AtomicInteger(0x5345_0000);
    private final Set<UUID> concealed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Mouse> mice = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pinned = new ConcurrentHashMap<>();
    private final Map<UUID, Filter> filters = new ConcurrentHashMap<>();
    /** Players held still for a client-placed pointer: their client is told they fly at a speed of nothing. */
    private final Set<UUID> held = ConcurrentHashMap.newKeySet();
    private final Logger log;

    public Pipeline(Nms nms, Logger log) {
        this.nms = nms;
        this.log = log;
    }

    /** A seated player's mouse. Every method runs on the netty thread, as the packet arrives. */
    public interface Mouse {
        /** They turned their head to (yaw, pitch). */
        void turned(float yaw, float pitch);

        /** Their client took a teleport the server sent: every rotation after this is from after it. */
        void teleported();

        /** They clicked or scrolled; the rotation that comes next is where they were looking when they did. */
        default void clicked() {}

        /** One of their client's ticks ended: whatever it sent that tick (clicks, a rotation) has arrived. */
        default void tickEnded() {}
    }

    private record Pending(UUID player, Runnable then) {}

    /**
     * Hand [player]'s turning to [mouse] as each packet arrives rather than a tick later, and stop the
     * server's own position updates for entity [pointer] (if not -1) reaching them: the engine moves it
     * itself, with {@link #sendNow}, and an update the server worked out a moment earlier would pull it
     * back. Needs the handler {@link #conceal} puts in.
     */
    public void watch(Player player, Mouse mouse, int pointer) {
        mice.put(player.getUniqueId(), mouse);
        if (pointer >= 0) pinned.put(player.getUniqueId(), pointer);
        else pinned.remove(player.getUniqueId());
    }

    public void unwatch(UUID player) {
        mice.remove(player);
        pinned.remove(player);
    }

    /** Write [packet] to [player] now, from any thread, past the filter (so a pinned entity's move goes through). */
    public void sendNow(UUID player, Object packet) {
        Filter f = filters.get(player);
        ChannelHandlerContext ctx = f == null ? null : f.ctx;
        if (ctx != null) ctx.writeAndFlush(packet, ctx.voidPromise());
    }

    /**
     * While [on], every abilities packet to [player] says they fly, cannot stop flying, and move at a
     * speed of nothing ({@link Nms#heldAbilities}); the server keeps their real abilities, and sending
     * those again (see {@link Nms#resendAbilities}) after this is turned off puts them back. Needs the
     * handler {@link #conceal} puts in.
     */
    public void hold(UUID player, boolean on) {
        if (on) held.add(player);
        else held.remove(player);
    }

    /**
     * Run [then] (on the netty thread) once the client has handled everything sent to it so far: it
     * answers a ping only after every packet before it. Never run if the player leaves.
     */
    public void afterClientCaughtUp(Player player, Runnable then) {
        Channel channel = nms.channel(player);
        if (channel == null) return;
        int id = pingIds.incrementAndGet();
        waiting.put(id, new Pending(player.getUniqueId(), then));
        channel.eventLoop().execute(() -> {
            ChannelPipeline p = channel.pipeline();
            if (!ours(p.get(PONGS))) install(p, PONGS, new Pongs());
            if (!ours(p.get(PONGS))) waiting.remove(id);
        });
        // Written now rather than with the next tick: the patches it follows went out the same way.
        if (!nms.writeNow(player, List.of(nms.ping(id)), ok -> { if (!ok) waiting.remove(id); })) waiting.remove(id);
    }

    /** Catches the answers to the engine's pings; the server ignores pongs, so they pass on as well. */
    private final class Pongs extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Integer id = nms.pongId(msg);
            if (id != null) {
                Pending w = waiting.remove(id);
                if (w != null) w.then.run();
            }
            ctx.fireChannelRead(msg);
        }

        Pipeline owner() {
            return Pipeline.this;
        }
    }

    /**
     * Make the client believe the player's inventory is empty for as long as [on] holds, whatever the
     * server sends; the server keeps the real items. A one-off empty inventory is not enough: Paper
     * resends the held item after every cancelled click on an entity, and scrolling then pops the item's
     * name up over the view.
     */
    public void conceal(Player player, boolean on) {
        if (on) {
            concealed.add(player.getUniqueId());
            Channel channel = nms.channel(player);
            if (channel == null) return;
            Object handle = nms.handle(player);
            // On the event loop, so it is in place before the resend below is written (the loop runs tasks in order).
            channel.eventLoop().execute(() -> {
                ChannelPipeline p = channel.pipeline();
                if (ours(p.get(FILTER))) return;
                Filter f = new Filter(player.getUniqueId(), handle);
                if (install(p, FILTER, f)) filters.put(player.getUniqueId(), f);
            });
        } else if (!concealed.remove(player.getUniqueId())) {
            return;
        }
        player.updateInventory();
    }

    public void forget(UUID player) {
        concealed.remove(player);
        held.remove(player);
        unwatch(player);
        filters.remove(player);
        // A ping the player never answered would otherwise keep its view, canvas and all, for good.
        waiting.values().removeIf(w -> w.player.equals(player));
    }

    /**
     * Whether [handler] is this Pipeline's own. A handler of the same name can be left in a channel by an
     * earlier load of the plugin (a reload, or a disable and enable): it reads that load's maps, so it
     * would swallow this one's pongs and never see its seated players.
     */
    private boolean ours(ChannelHandler handler) {
        if (handler instanceof Filter f) return f.owner() == this;
        if (handler instanceof FrameMaps m) return m.owner() == this;
        return handler instanceof Pongs g && g.owner() == this;
    }

    /**
     * Puts [handler] in as [name], in place of any other handler of that name. False if the anchor is
     * missing, which happens only once netty has torn the channel down; then there is nothing to handle.
     */
    private boolean install(ChannelPipeline p, String name, ChannelHandler handler) {
        if (p.get(nms.packetHandlerName) == null) return false;
        if (p.get(name) != null) p.replace(name, name, handler);
        else p.addBefore(nms.packetHandlerName, name, handler);
        return true;
    }

    /**
     * Takes this Pipeline's handlers out of [players]' connections, so a disabled plugin leaves nothing
     * behind that reads its maps (and keeps its classes loaded). On each channel's own thread, like
     * every other change to a pipeline here.
     */
    public void close(Collection<? extends Player> players) {
        for (Player player : players) {
            Channel channel = nms.channel(player);
            if (channel == null) continue;
            try {
                channel.eventLoop().execute(() -> {
                    ChannelPipeline p = channel.pipeline();
                    for (String name : List.of(FILTER, PONGS, FRAMES)) if (ours(p.get(name))) p.remove(name);
                });
            } catch (RejectedExecutionException e) {
                // The event loop is shutting down with the server, and the channel with it.
            }
        }
        waiting.clear();
        filters.clear();
        mice.clear();
        pinned.clear();
        concealed.clear();
        held.clear();
    }

    /**
     * [player]'s frames on this connection, as {@link Wire#frames}: the handler that keeps them, once it is in
     * their connection. Until then null, and it is put in: nothing is pointed elsewhere before it can tell a frame
     * sent afresh which map to show.
     */
    public Wire.Frames frames(Player player) {
        Channel channel = nms.channel(player);
        if (channel == null) return null;
        if (channel.pipeline().get(FRAMES) instanceof FrameMaps m && m.owner() == this) return m.added ? m : null;
        try {
            channel.eventLoop().execute(() -> {
                ChannelPipeline p = channel.pipeline();
                if (!ours(p.get(FRAMES))) install(p, FRAMES, new FrameMaps());
            });
        } catch (RejectedExecutionException e) {
            // The event loop is shutting down with the server.
        }
        return null;
    }

    /**
     * Which map each frame shows one player, where the sender has pointed it at another map of its wall: a
     * scroll by whole map rows shows the maps the client already has in other frames. A frame the server sends
     * this player afresh (spawned, shown again, resent after a cancelled click) would show its own map, so it is
     * rewritten on its way out to show the one in [pointing]. Kept for as long as the connection lasts, which is
     * as long as the client keeps its frames and maps.
     */
    private final class FrameMaps extends ChannelOutboundHandlerAdapter implements Wire.Frames, SharedCompression.LeavesMapsAlone {
        /** By wall: the map each tile shows, for walls where any is not its own. Written by the sender thread. */
        private final Map<MapWall, int[]> pointing = Collections.synchronizedMap(new WeakHashMap<>());
        volatile boolean added;
        private boolean warned;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            added = true;
        }

        Pipeline owner() {
            return Pipeline.this;
        }

        @Override
        public int[] pointing(MapWall wall) {
            return pointing.get(wall);
        }

        @Override
        public void point(MapWall wall, int[] to) {
            for (int t = 0; t < to.length; t++) {
                if (to[t] != wall.mapId(t)) {
                    pointing.put(wall, to.clone());
                    return;
                }
            }
            pointing.remove(wall);
        }

        private int mapOf(int entityId) {
            synchronized (pointing) {
                for (Map.Entry<MapWall, int[]> e : pointing.entrySet()) {
                    int t = e.getKey().tileOfEntity(entityId);
                    if (t >= 0) return e.getValue()[t];
                }
            }
            return -1;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            Object out = msg;
            if (!pointing.isEmpty()) {
                try {
                    out = nms.pointFrames(msg, this::mapOf);
                } catch (RuntimeException e) {
                    // Never take the connection down with it: the frame then shows its own map until the next change.
                    if (!warned) log.log(Level.WARNING, "Could not point a frame sent afresh at its map", e);
                    warned = true;
                }
            }
            ctx.write(out, promise);
        }
    }

    /** Stays in the pipeline once added; it passes everything through untouched while its player is not seated. */
    private final class Filter extends ChannelDuplexHandler implements SharedCompression.LeavesMapsAlone {
        private final UUID id;
        private final Object player;
        volatile ChannelHandlerContext ctx;

        Filter(UUID id, Object player) {
            this.id = id;
            this.player = player;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        Pipeline owner() {
            return Pipeline.this;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Mouse mouse = mice.get(id);
            if (mouse != null) {
                try {
                    if (nms.acceptsTeleport(msg)) {
                        mouse.teleported();
                    } else if (nms.isClick(msg)) {
                        mouse.clicked();
                    } else if (nms.isTickEnd(msg)) {
                        mouse.tickEnded();
                    } else {
                        float[] turn = nms.rotation(msg);
                        if (turn != null) mouse.turned(turn[0], turn[1]);
                    }
                } catch (RuntimeException e) {
                    // Never take the connection down with it.
                    log.log(Level.WARNING, "A view's mouse handler failed", e);
                }
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Integer pin = pinned.get(id);
            if (pin != null && nms.movedEntity(msg) == pin) {
                promise.trySuccess();
                return;
            }
            if (held.contains(id) && nms.isAbilities(msg)) msg = nms.heldAbilities(msg);
            ctx.write(concealed.contains(id) ? blank(msg) : msg, promise);
        }

        private Object blank(Object msg) {
            if (nms.contentPacket.isInstance(msg)) {
                int container = (int) Nms.get(nms.contentContainer, msg);
                List<?> items = (List<?>) Nms.get(nms.contentItems, msg);
                int keep = container == 0 ? 0 : items.size() - PLAYER_SLOTS;
                List<Object> out = new ArrayList<>(items.size());
                for (int i = 0; i < items.size(); i++) out.add(i < keep ? items.get(i) : nms.emptyItem);
                return Nms.make(nms.newContentPacket, container, Nms.get(nms.contentState, msg), out, Nms.get(nms.contentCarried, msg));
            }
            if (nms.slotPacket.isInstance(msg)) {
                int container = (int) Nms.get(nms.slotContainer, msg);
                int slot = (int) Nms.get(nms.slotSlot, msg);
                if (!isPlayerSlot(container, slot)) return msg;
                return Nms.make(nms.newSlotPacket, container, Nms.get(nms.slotState, msg), slot, nms.emptyItem);
            }
            if (nms.inventoryPacket.isInstance(msg)) {
                return Nms.make(nms.newInventoryPacket, Nms.get(nms.inventorySlot, msg), nms.emptyItem);
            }
            return msg;
        }

        private boolean isPlayerSlot(int containerId, int slot) {
            if (containerId == 0) return true;
            Object menu = Nms.get(nms.containerMenu, player);
            if (menu == null || (int) Nms.get(nms.menuContainerId, menu) != containerId) return false;
            return slot >= ((List<?>) Nms.get(nms.menuSlots, menu)).size() - PLAYER_SLOTS;
        }
    }
}
