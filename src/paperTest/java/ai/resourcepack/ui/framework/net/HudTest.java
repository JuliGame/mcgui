package ai.resourcepack.ui.framework.net;

import ai.resourcepack.ui.framework.Settings;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a client is sent while a page hides the sidebar, boss bars and action bar ({@link Hud}), on the game's own packet
 * classes: the client must end with the state the server last meant, whatever happened while the page was up.
 */
class HudTest {
    private static final Settings.Hidden ALL = new Settings.Hidden(true, true, true);
    /** Hiding also sends an empty action bar; the tests that count packets leave it out. */
    private static final Settings.Hidden NO_BAR = new Settings.Hidden(true, true, false);
    private static HudPackets packets;
    private final UUID player = UUID.randomUUID();
    private final Scoreboard board = new Scoreboard();
    private Hud hud;
    private EmbeddedChannel channel;

    @BeforeEach
    void connection() throws ReflectiveOperationException {
        java.util.Objects.requireNonNull(PaperNet.GAME); // the game's bootstrap
        if (packets == null) packets = new HudPackets();
        hud = hud(NO_BAR);
    }

    private Hud hud(Settings.Hidden parts) {
        Hud h = new Hud(null, packets, parts, player, Logger.getAnonymousLogger(), Map.of(), Map.of());
        channel = new EmbeddedChannel(h);
        return h;
    }

    private Objective objective(String name) {
        return board.addObjective(name, ObjectiveCriteria.DUMMY, Component.literal(name), ObjectiveCriteria.RenderType.INTEGER, false, null);
    }

    private List<Object> sent() {
        List<Object> out = new ArrayList<>();
        for (Object o; (o = channel.readOutbound()) != null; ) out.add(o);
        return out;
    }

    /** A boss bar: 26.2 gives each an id of its own at construction, 1.21.11 makes one. */
    private static ServerBossEvent bar(String name) throws ReflectiveOperationException {
        Component title = Component.literal(name);
        try {
            return ServerBossEvent.class.getConstructor(Component.class, BossEvent.BossBarColor.class, BossEvent.BossBarOverlay.class)
                    .newInstance(title, BossEvent.BossBarColor.PINK, BossEvent.BossBarOverlay.PROGRESS);
        } catch (NoSuchMethodException newer) {
            return ServerBossEvent.class.getConstructor(UUID.class, Component.class, BossEvent.BossBarColor.class, BossEvent.BossBarOverlay.class)
                    .newInstance(UUID.randomUUID(), title, BossEvent.BossBarColor.PINK, BossEvent.BossBarOverlay.PROGRESS);
        }
    }

    @Test
    void theSidebarIsClearedAtAPageAndTheLastOneSentMeanwhileComesBack() {
        Objective first = objective("first"), second = objective("second");
        var shown = new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, first);
        channel.writeOutbound(shown);
        assertEquals(List.of(shown), sent(), "a sidebar passes while no page is up");

        hud.hide();
        List<Object> cleared = sent();
        assertEquals(1, cleared.size());
        var clear = (ClientboundSetDisplayObjectivePacket) cleared.get(0);
        assertEquals(DisplaySlot.SIDEBAR, clear.getSlot());
        assertTrue(clear.getObjectiveName() == null || clear.getObjectiveName().isEmpty(), "the client is told to show no objective");

        var next = new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, second);
        channel.writeOutbound(next);
        assertEquals(List.of(), sent(), "a sidebar set at a page does not reach the client");

        hud.show();
        assertEquals(List.of(next), sent(), "the last one the server set is what they get back, and only it");
    }

    @Test
    void aSidebarTheServerClearedAtAPageStaysCleared() {
        Objective o = objective("o");
        channel.writeOutbound(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, o));
        sent();
        hud.hide();
        sent();
        channel.writeOutbound(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, null));
        hud.show();
        assertEquals(List.of(), sent());
    }

    @Test
    void anObjectiveRemovedAtAPageIsNotShownAgain() {
        Objective o = objective("gone");
        channel.writeOutbound(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, o));
        sent();
        hud.hide();
        sent();
        var removal = new ClientboundSetObjectivePacket(o, ClientboundSetObjectivePacket.METHOD_REMOVE);
        channel.writeOutbound(removal);
        assertEquals(List.of(removal), sent(), "the removal itself is sent: the client has the objective");
        hud.show();
        assertEquals(List.of(), sent(), "a slot is not pointed at an objective the client no longer has");
    }

    @Test
    void theTabListAndNamesAreNotTouched() {
        Objective o = objective("tab");
        var list = new ClientboundSetDisplayObjectivePacket(DisplaySlot.LIST, o);
        var below = new ClientboundSetDisplayObjectivePacket(DisplaySlot.BELOW_NAME, o);
        channel.writeOutbound(list);
        hud.hide();
        assertEquals(List.of(list), sent(), "nothing is cleared for the tab list");
        channel.writeOutbound(below);
        assertEquals(List.of(below), sent(), "and what is set for them at a page goes through");
    }

    @Test
    void aTeamColouredSidebarIsHiddenToo() {
        Objective o = objective("team");
        channel.writeOutbound(new ClientboundSetDisplayObjectivePacket(DisplaySlot.TEAM_RED, o));
        sent();
        hud.hide();
        var clear = (ClientboundSetDisplayObjectivePacket) sent().get(0);
        assertEquals(DisplaySlot.TEAM_RED, clear.getSlot());
    }

    @Test
    void bossBarsAreRemovedAtAPageAndComeBackAsTheyAreNow() throws ReflectiveOperationException {
        ServerBossEvent kept = bar("kept"), removed = bar("removed"), added = bar("added");
        channel.writeOutbound(ClientboundBossEventPacket.createAddPacket(kept), ClientboundBossEventPacket.createAddPacket(removed));
        sent();
        hud.hide();
        List<Object> out = sent();
        assertEquals(2, out.size(), "a removal for each bar the client has");

        kept.setProgress(0.75f);
        kept.setName(Component.literal("kept, renamed"));
        var progress = ClientboundBossEventPacket.createUpdateProgressPacket(kept);
        var name = ClientboundBossEventPacket.createUpdateNamePacket(kept);
        var addedPacket = ClientboundBossEventPacket.createAddPacket(added);
        channel.writeOutbound(progress, name, addedPacket, ClientboundBossEventPacket.createRemovePacket(removed.getId()));
        assertEquals(List.of(), sent(), "nothing about a bar reaches the client at a page");

        var keptAdd = ClientboundBossEventPacket.createAddPacket(kept);
        hud.show();
        List<Object> back = sent();
        // The kept bar: its add as sent, then the updates after it. The added one: its add. The removed one: nothing.
        assertEquals(4, back.size(), back.toString());
        assertSame(progress, back.get(1));
        assertSame(name, back.get(2));
        assertSame(addedPacket, back.get(3));
        assertTrue(back.get(0) instanceof ClientboundBossEventPacket);
        assertEquals(keptAdd.getClass(), back.get(0).getClass());
    }

    @Test
    void actionBarsAreDroppedAtAPageApartFromMCGUIsOwn() {
        hud = hud(ALL);
        var bar = new ClientboundSetActionBarTextPacket(Component.literal("plugin's bar"));
        var chat = new ClientboundSystemChatPacket(Component.literal("plugin's bar again"), true);
        var message = new ClientboundSystemChatPacket(Component.literal("a chat message"), false);
        channel.writeOutbound(bar);
        assertEquals(List.of(bar), sent(), "an action bar passes while no page is up");

        hud.hide();
        List<Object> hidden = sent();
        assertEquals(1, hidden.size());
        assertEquals("", ((ClientboundSetActionBarTextPacket) hidden.get(0)).text().getString(), "an empty one clears what shows");

        channel.writeOutbound(bar, chat, message);
        assertEquals(List.of(message), sent(), "both kinds of action bar are dropped, and chat is not");

        OwnBars.expect(player, "MC GUI says");
        var own = new ClientboundSetActionBarTextPacket(Component.literal("MC GUI says"));
        channel.writeOutbound(own, own);
        assertEquals(List.of(own), sent(), "MC GUI's own message passes once, and a repeat of it does not");

        hud.show();
        assertEquals(List.of(), sent(), "nothing is replayed: an action bar is gone in three seconds anyway");
    }

    /** What a plugin that encodes its own packets (PacketEvents' sendPacket) writes: the id and body, not a packet object. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static io.netty.buffer.ByteBuf finished(Object packet) {
        io.netty.buffer.ByteBuf out = io.netty.buffer.Unpooled.buffer();
        ((net.minecraft.network.codec.StreamCodec) PaperNet.GAME.codec()).encode(out, packet);
        return out;
    }

    @Test
    void finishedPacketsAreReadAsThePacketsTheyAre() throws Exception {
        EmbeddedChannel ch = PaperNet.server(256, null);
        ch.pipeline().addBefore("packet_handler", "uiengine_hud", hud = new Hud(null, packets, ALL, player, Logger.getAnonymousLogger(), Map.of(), Map.of()));
        ServerBossEvent event = bar("raw bar");
        ch.writeOutbound(finished(ClientboundBossEventPacket.createAddPacket(event)), finished(new ClientboundSetActionBarTextPacket(Component.literal("raw"))));
        assertEquals(2, ch.outboundMessages().size(), "bytes of a boss bar and an action bar go on while no page is up");
        ch.releaseOutbound();

        hud.hide();
        ch.runPendingTasks();
        int clearing = ch.outboundMessages().size();
        assertEquals(2, clearing, "a boss bar sent as bytes was known, and is removed, with the empty action bar");
        ch.releaseOutbound();

        event.setProgress(0.5f);
        ch.writeOutbound(finished(ClientboundBossEventPacket.createUpdateProgressPacket(event)), finished(new ClientboundSetActionBarTextPacket(Component.literal("raw again"))),
                finished(new ClientboundSystemChatPacket(Component.literal("overlay"), true)), finished(new ClientboundSystemChatPacket(Component.literal("chat"), false)));
        assertEquals(1, ch.outboundMessages().size(), "at a page the bar's update and both action bars are dropped, chat is not");
        ch.releaseOutbound();

        hud.show();
        ch.runPendingTasks();
        assertEquals(2, ch.outboundMessages().size(), "closing sends the bar again, with its latest progress");
        ch.releaseOutbound();
    }

    @Test
    void whatIsOffStaysOn() {
        hud = hud(new Settings.Hidden(false, true, false));
        Objective o = objective("o");
        var sidebar = new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, o);
        var bar = new ClientboundSetActionBarTextPacket(Component.literal("stays"));
        channel.writeOutbound(sidebar);
        sent();
        hud.hide();
        channel.writeOutbound(sidebar, bar);
        assertEquals(List.of(sidebar, bar), sent(), "no clearing, and the sidebar and action bar go on being sent");
    }
}
