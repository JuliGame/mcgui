package ai.resourcepack.ui.framework.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

/**
 * The HUD watcher's second place in a connection, just on the wire side of PacketEvents' encoder. A plugin that sends with
 * PacketEvents' {@code sendPacketSilently} (AuraSkills' action bar does) writes its finished bytes from that encoder's own
 * place, nearer the wire than {@link Hud}, so they never pass it. They pass here, where this hands them to the {@link Hud}
 * as the packets they are. Everything {@link Hud} has already judged comes through here too, as bytes, and is let by
 * ({@link Hud#passing}), so nothing is judged twice. Only put in where PacketEvents is.
 */
final class HudWire extends ChannelOutboundHandlerAdapter implements SharedCompression.LeavesMapsAlone {
    private final Pipeline owner;
    private final Hud hud;

    HudWire(Pipeline owner, Hud hud) {
        this.owner = owner;
        this.hud = hud;
    }

    Pipeline owner() {
        return owner;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf bytes && !hud.passing() && hud.dropsRaw(ctx.channel(), bytes)) {
            ReferenceCountUtil.release(msg);
            promise.trySuccess();
            return;
        }
        ctx.write(msg, promise);
    }
}
