package ai.resourcepack.ui.framework.net;

import ai.resourcepack.ui.framework.Settings;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One player's connection, watching the sidebar, boss bars and action bar the server sends them and hiding those while a
 * page is open ({@link Pipeline#hideHud}). It sits in the connection from join (it has to see what was sent before a page
 * opened), and passes everything on untouched while its player has no page.
 *
 * <p>What it keeps, always: the last packet for each sidebar slot, and each boss bar's packets by id (the add, and the latest
 * name, progress, style and properties). Hiding takes the sidebar and the bars off the client with packets of its own and
 * drops the ones that would show them again, still keeping them; showing again sends what was kept, so the client ends as
 * it would have been with nothing hidden. Scores and teams are not touched: they draw nothing without a sidebar. Every
 * method runs on the connection's event loop, which also runs every write, so none of this is shared between threads.
 */
final class Hud extends ChannelOutboundHandlerAdapter implements SharedCompression.LeavesMapsAlone {
    /** A boss bar as the client has it: the packet that added it, and the latest of each kind that changed it since. */
    private static final class Bar {
        Object add, progress, name, style, properties;

        Bar(Object add) {
            this.add = add;
        }
    }

    private final Pipeline owner;
    private final HudPackets packets;
    private final Settings.Hidden parts;
    private final UUID id;
    private final Logger log;
    private final Map<Object, Object> sidebars = new LinkedHashMap<>();
    private final Map<UUID, Bar> bars = new LinkedHashMap<>();
    private volatile ChannelHandlerContext ctx;
    private boolean hidden;
    private boolean warned;

    /** [sidebars] and [bars] are what the connection already carries (see {@link HudPackets#sidebars}). */
    Hud(Pipeline owner, HudPackets packets, Settings.Hidden parts, UUID id, Logger log, Map<Object, Object> sidebars, Map<UUID, Object> bars) {
        this.owner = owner;
        this.packets = packets;
        this.parts = parts;
        this.id = id;
        this.log = log;
        if (parts.scoreboard()) this.sidebars.putAll(sidebars);
        if (parts.bossBars()) bars.forEach((bar, add) -> this.bars.put(bar, new Bar(add)));
    }

    Pipeline owner() {
        return owner;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    /** Runs [then] on the connection's event loop, in order with the writes. */
    void run(Runnable then) {
        ChannelHandlerContext c = ctx;
        if (c == null) return;
        try {
            c.executor().execute(then);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // The event loop is shutting down with the server, and the connection with it.
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        try {
            if (watch(msg)) {
                promise.trySuccess();
                return;
            }
        } catch (RuntimeException e) {
            // Never take the connection down with it: the packet goes on, and the part may show over a page.
            if (!warned) log.log(Level.WARNING, "Could not read a HUD packet sent to a player", e);
            warned = true;
        }
        ctx.write(msg, promise);
    }

    /** Takes [msg] into account; whether it is to be dropped (it would draw a hidden part). */
    private boolean watch(Object msg) {
        if (parts.scoreboard()) {
            if (packets.isDisplay(msg)) {
                Object slot = packets.sidebarSlot(msg);
                if (slot == null) return false;
                if (packets.objectiveOf(msg).isEmpty()) sidebars.remove(slot);
                else sidebars.put(slot, msg);
                return hidden;
            }
            String removed = packets.removedObjective(msg);
            if (removed != null) {
                // The client takes the objective off every slot it was in; showing it again would show nothing.
                sidebars.values().removeIf(shown -> packets.objectiveOf(shown).equals(removed));
                return false;
            }
        }
        if (parts.bossBars() && packets.isBoss(msg)) {
            track(msg);
            return hidden;
        }
        if (parts.actionBar() && hidden) {
            String text = packets.actionBarText(msg);
            return text != null && !OwnBars.take(id, text);
        }
        return false;
    }

    private void track(Object msg) {
        UUID bar = packets.bossOf(msg);
        HudPackets.BossKind kind = packets.kindOf(msg);
        if (kind == HudPackets.BossKind.ADD) {
            bars.put(bar, new Bar(msg));
            return;
        }
        if (kind == HudPackets.BossKind.REMOVE) {
            bars.remove(bar);
            return;
        }
        Bar b = bars.get(bar);
        if (b == null) return;
        switch (kind) {
            case PROGRESS -> b.progress = msg;
            case NAME -> b.name = msg;
            case STYLE -> b.style = msg;
            default -> b.properties = msg;
        }
    }

    /** Take what a page covers off the client. */
    void hide() {
        if (hidden) return;
        hidden = true;
        ChannelHandlerContext c = ctx;
        if (c == null || !c.channel().isActive()) return;
        if (parts.scoreboard()) for (Object slot : sidebars.keySet()) c.write(packets.clearing(slot));
        if (parts.bossBars()) for (UUID bar : bars.keySet()) c.write(packets.removal(bar));
        if (parts.actionBar()) c.write(packets.emptyBar);
        c.flush();
    }

    /** Put back what [hide] took off, as the server last sent it. */
    void show() {
        if (!hidden) return;
        hidden = false;
        ChannelHandlerContext c = ctx;
        if (c == null || !c.channel().isActive()) return;
        if (parts.scoreboard()) for (Object shown : sidebars.values()) c.write(shown);
        if (parts.bossBars()) {
            for (Bar b : bars.values()) {
                c.write(b.add);
                for (Object update : new Object[] {b.progress, b.name, b.style, b.properties}) if (update != null) c.write(update);
            }
        }
        c.flush();
    }
}
