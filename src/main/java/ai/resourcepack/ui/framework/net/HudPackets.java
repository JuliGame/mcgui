package ai.resourcepack.ui.framework.net;

import org.bukkit.Bukkit;
import org.bukkit.boss.KeyedBossBar;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The packets that draw the parts of the game's HUD a plugin owns: the sidebar scoreboard, boss bars and the action bar
 * ({@link Pipeline#hideHud}). Looked up once, like everything in {@link Nms}, and the same in every version MC GUI runs on
 * (checked on Paper 1.21.11 and 26.2, and the 26.1.2 and 26.3 server jars).
 */
final class HudPackets {
    private static final MethodHandles.Lookup L = MethodHandles.lookup();

    /** What a boss bar packet does to its bar. */
    enum BossKind { ADD, REMOVE, PROGRESS, NAME, STYLE, PROPERTIES }

    // The sidebar: a display slot (an enum) shows an objective by name; "" shows none.
    private final Class<?> displayPacket;           // ClientboundSetDisplayObjectivePacket
    private final MethodHandle displaySlot;         // #getSlot
    private final MethodHandle displayObjective;    // #getObjectiveName
    private final MethodHandle newDisplay;          // new ClientboundSetDisplayObjectivePacket(DisplaySlot, Objective)
    private final Class<?> objectivePacket;         // ClientboundSetObjectivePacket
    private final MethodHandle objectiveName;       // #getObjectiveName
    private final MethodHandle objectiveMethod;     // #getMethod (METHOD_REMOVE = 1)
    private final MethodHandle scoreboardDisplay;   // Scoreboard#getDisplayObjective(DisplaySlot)
    private final Object[] slots;                   // DisplaySlot's constants
    private final Set<Object> sidebarSlots;         // the ones that are a sidebar: SIDEBAR and the team colours' (not LIST, not BELOW_NAME)

    // Boss bars: an id, and an operation on it.
    private final Class<?> bossPacket;              // ClientboundBossEventPacket
    private final MethodHandle bossId;              // its private id
    private final MethodHandle bossOperation;       // its private operation
    private final MethodHandle operationType;       // Operation#getType, an OperationType
    private final Map<Object, BossKind> kinds = new HashMap<>(); // OperationType's constants
    private final MethodHandle newBossRemove;       // ClientboundBossEventPacket.createRemovePacket(UUID)
    private final MethodHandle newBossAdd;          // ClientboundBossEventPacket.createAddPacket(BossEvent)

    // The action bar: its own packet, or a system chat message that is an overlay.
    private final Class<?> actionPacket;            // ClientboundSetActionBarTextPacket
    private final MethodHandle actionText;          // #text
    private final Class<?> chatPacket;              // ClientboundSystemChatPacket
    private final MethodHandle chatContent;         // #content
    private final MethodHandle chatOverlay;         // #overlay
    private final MethodHandle componentString;     // Component#getString
    final Object emptyBar;                          // an action bar of no text, which clears the one showing

    HudPackets() throws ReflectiveOperationException {
        String game = "net.minecraft.network.protocol.game.";
        displayPacket = Reflect.cls(game + "ClientboundSetDisplayObjectivePacket");
        Class<?> slot = Reflect.cls("net.minecraft.world.scores.DisplaySlot");
        Class<?> objective = Reflect.cls("net.minecraft.world.scores.Objective");
        displaySlot = L.unreflect(Reflect.method(displayPacket, "getSlot"));
        displayObjective = L.unreflect(Reflect.method(displayPacket, "getObjectiveName"));
        newDisplay = Reflect.constructor(L, displayPacket, MethodType.methodType(void.class, slot, objective));
        objectivePacket = Reflect.cls(game + "ClientboundSetObjectivePacket");
        objectiveName = L.unreflect(Reflect.method(objectivePacket, "getObjectiveName"));
        objectiveMethod = L.unreflect(Reflect.method(objectivePacket, "getMethod"));
        scoreboardDisplay = L.unreflect(Reflect.method(Reflect.cls("net.minecraft.world.scores.Scoreboard"), "getDisplayObjective", slot));
        slots = slot.getEnumConstants();
        // By the constants themselves, not their names: Spigot before 26.1 names them as obfuscated.
        Set<Object> sidebar = new HashSet<>();
        for (String name : List.of("SIDEBAR", "TEAM_BLACK", "TEAM_DARK_BLUE", "TEAM_DARK_GREEN", "TEAM_DARK_AQUA", "TEAM_DARK_RED",
                "TEAM_DARK_PURPLE", "TEAM_GOLD", "TEAM_GRAY", "TEAM_DARK_GRAY", "TEAM_BLUE", "TEAM_GREEN", "TEAM_AQUA", "TEAM_RED",
                "TEAM_LIGHT_PURPLE", "TEAM_YELLOW", "TEAM_WHITE")) {
            sidebar.add(Reflect.field(slot, name).get(null));
        }
        sidebarSlots = Set.copyOf(sidebar);

        bossPacket = Reflect.cls(game + "ClientboundBossEventPacket");
        bossId = L.unreflectGetter(Reflect.field(bossPacket, "id"));
        bossOperation = L.unreflectGetter(Reflect.field(bossPacket, "operation"));
        // A public method of a class that is not (an interface nested in the packet): opened, or unreflecting it is refused.
        java.lang.reflect.Method type = Reflect.method(Reflect.cls(game + "ClientboundBossEventPacket$Operation"), "getType");
        type.setAccessible(true);
        operationType = L.unreflect(type);
        Class<?> types = Reflect.cls(game + "ClientboundBossEventPacket$OperationType");
        kinds.put(constant(types, "ADD"), BossKind.ADD);
        kinds.put(constant(types, "REMOVE"), BossKind.REMOVE);
        kinds.put(constant(types, "UPDATE_PROGRESS"), BossKind.PROGRESS);
        kinds.put(constant(types, "UPDATE_NAME"), BossKind.NAME);
        kinds.put(constant(types, "UPDATE_STYLE"), BossKind.STYLE);
        kinds.put(constant(types, "UPDATE_PROPERTIES"), BossKind.PROPERTIES);
        newBossRemove = L.unreflect(Reflect.method(bossPacket, "createRemovePacket", UUID.class));
        newBossAdd = L.unreflect(Reflect.method(bossPacket, "createAddPacket", Reflect.cls("net.minecraft.world.BossEvent")));

        actionPacket = Reflect.cls(game + "ClientboundSetActionBarTextPacket");
        actionText = L.unreflect(Reflect.method(actionPacket, "text"));
        chatPacket = Reflect.cls(game + "ClientboundSystemChatPacket");
        chatContent = L.unreflect(Reflect.method(chatPacket, "content"));
        chatOverlay = L.unreflect(Reflect.method(chatPacket, "overlay"));
        Class<?> component = Reflect.cls("net.minecraft.network.chat.Component");
        componentString = L.unreflect(Reflect.method(component, "getString"));
        Object empty = Reflect.method(component, "empty").invoke(null);
        try {
            emptyBar = Reflect.constructor(L, actionPacket, MethodType.methodType(void.class, component)).invoke(empty);
        } catch (Throwable t) {
            throw new ReflectiveOperationException("Cannot make an empty action bar", t);
        }
    }

    /** A constant of [type], an enum the game does not make public. */
    private static Object constant(Class<?> type, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field f = Reflect.field(type, name);
        f.setAccessible(true);
        return f.get(null);
    }

    // ------------------------------------------------------------------ the sidebar

    /** Whether [msg] sets what a display slot shows. */
    boolean isDisplay(Object msg) {
        return displayPacket.isInstance(msg);
    }

    /** The slot [display] sets, but only if it is a sidebar's (the plain one or a team colour's); else null. */
    Object sidebarSlot(Object display) {
        try {
            Object slot = displaySlot.invoke(display);
            return isSidebar(slot) ? slot : null;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Whether [slot] is a sidebar's: not the tab list's, and not the one under names. */
    private boolean isSidebar(Object slot) {
        return sidebarSlots.contains(slot);
    }

    /** What [display] shows ("" for nothing). */
    String objectiveOf(Object display) {
        try {
            String name = (String) displayObjective.invoke(display);
            return name == null ? "" : name;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** A packet that takes whatever [slot] shows off it. */
    Object clearing(Object slot) {
        try {
            return newDisplay.invoke(slot, null);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The objective [msg] removes (from the client's scoreboard, and so from every slot), or null if it removes none. */
    String removedObjective(Object msg) {
        if (!objectivePacket.isInstance(msg)) return null;
        try {
            return (int) objectiveMethod.invoke(msg) == 1 ? (String) objectiveName.invoke(msg) : null;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * What the player's own Bukkit scoreboard shows in each sidebar slot, as the packets that would show it: the server
     * sends those when the player joins, before any plugin's join handler can see them go by. Empty if it cannot be read.
     */
    Map<Object, Object> sidebars(Player player) {
        Map<Object, Object> out = new LinkedHashMap<>();
        try {
            Object board = Reflect.method(player.getScoreboard().getClass(), "getHandle").invoke(player.getScoreboard());
            for (Object slot : slots) {
                if (!isSidebar(slot)) continue;
                Object shown = scoreboardDisplay.invoke(board, slot);
                if (shown != null) out.put(slot, newDisplay.invoke(slot, shown));
            }
        } catch (Throwable t) {
            return Map.of();
        }
        return out;
    }

    // ------------------------------------------------------------------ boss bars

    boolean isBoss(Object msg) {
        return bossPacket.isInstance(msg);
    }

    UUID bossOf(Object boss) {
        try {
            return (UUID) bossId.invoke(boss);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    BossKind kindOf(Object boss) {
        try {
            return kinds.get(operationType.invoke(bossOperation.invoke(boss)));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    Object removal(UUID bar) {
        try {
            return newBossRemove.invoke(bar);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The boss bars (the server's own, a command's, a plugin's with a key) the player is shown, as the packets that add them. */
    Map<UUID, Object> bars(Player player) {
        Map<UUID, Object> out = new LinkedHashMap<>();
        try {
            for (Iterator<KeyedBossBar> it = Bukkit.getBossBars(); it.hasNext(); ) {
                KeyedBossBar bar = it.next();
                if (!bar.getPlayers().contains(player)) continue;
                Object event = Reflect.method(bar.getClass(), "getHandle").invoke(bar);
                Object add = newBossAdd.invoke(event);
                out.put(bossOf(add), add);
            }
        } catch (Throwable t) {
            return Map.of();
        }
        return out;
    }

    // ------------------------------------------------------------------ the action bar

    /** The text of [msg] if it is an action bar (either packet that draws one), else null. */
    String actionBarText(Object msg) {
        try {
            if (actionPacket.isInstance(msg)) return (String) componentString.invoke(actionText.invoke(msg));
            if (chatPacket.isInstance(msg) && (boolean) chatOverlay.invoke(msg)) return (String) componentString.invoke(chatContent.invoke(msg));
            return null;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException rethrow(Throwable t) throws T {
        throw (T) t;
    }
}
