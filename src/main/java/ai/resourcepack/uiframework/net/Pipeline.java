package ai.resourcepack.uiframework.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.ChannelPipeline;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Map<Integer, Runnable> waiting = new ConcurrentHashMap<>();
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

    /** A seated player turned their head to (yaw, pitch). Runs on the netty thread, as the packet arrives. */
    public interface Mouse {
        void turned(float yaw, float pitch);
    }

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
        waiting.put(id, then);
        channel.eventLoop().execute(() -> {
            ChannelPipeline p = channel.pipeline();
            if (p.get(PONGS) == null && p.get(nms.packetHandlerName) != null) p.addBefore(nms.packetHandlerName, PONGS, new Pongs());
            if (p.get(PONGS) == null) waiting.remove(id);
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
                Runnable r = waiting.remove(id);
                if (r != null) r.run();
            }
            ctx.fireChannelRead(msg);
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
                // The anchor is only missing once netty has torn the channel down; then there is nothing to filter.
                if (p.get(FILTER) == null && p.get(nms.packetHandlerName) != null) {
                    Filter f = new Filter(player.getUniqueId(), handle);
                    p.addBefore(nms.packetHandlerName, FILTER, f);
                    filters.put(player.getUniqueId(), f);
                }
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

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            Mouse mouse = mice.get(id);
            if (mouse != null) {
                float[] turn = nms.rotation(msg);
                if (turn != null) {
                    try {
                        mouse.turned(turn[0], turn[1]);
                    } catch (RuntimeException e) {
                        // Never take the connection down with it.
                        log.log(Level.WARNING, "A view's mouse handler failed", e);
                    }
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
