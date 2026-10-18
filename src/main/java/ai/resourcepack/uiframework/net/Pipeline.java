package ai.resourcepack.uiframework.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelPipeline;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The two netty handlers the engine puts in a player's connection: ping answers, and one for a seated
 * player that hides their inventory, reads their mouse the moment it arrives, and moves their pointer.
 */
public final class Pipeline {
    private static final String PONGS = "uiframework_pong";
    private static final String FILTER = "uiframework_conceal";
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
    private final Logger log;

    public Pipeline(Nms nms, Logger log) {
        this.nms = nms;
        this.log = log;
    }

    /** A seated player's mouse. Both run on the netty thread, as the packet arrives. */
    public interface Mouse {
        /** They turned their head to (yaw, pitch). */
        void turned(float yaw, float pitch);

        /** Their client took a teleport the server sent: every rotation after this is from after it. */
        void teleported();
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
        return handler instanceof Filter f ? f.owner() == this : handler instanceof Pongs g && g.owner() == this;
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
                    for (String name : List.of(FILTER, PONGS)) if (ours(p.get(name))) p.remove(name);
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
    }

    /** Stays in the pipeline once added; it passes everything through untouched while its player is not seated. */
    private final class Filter extends ChannelDuplexHandler {
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
