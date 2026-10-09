package ai.resourcepack.screens.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

/**
 * Everything Screen Engine needs that the Paper API cannot do, reached by reflection on the
 * Mojang-mapped server (Paper 1.20.5 and newer run Mojang's names), so the plugin compiles against
 * the plain API and survives a server update that leaves these few members alone. Every handle is
 * looked up once, at startup: a missing one fails the whole engine there, loudly, rather than the
 * first time somebody looks at a screen.
 *
 * Checked against Paper 26.2 and 26.3, which agree on every member used here.
 */
public final class Nms {
    private static final MethodHandles.Lookup L = MethodHandles.lookup();

    private final MethodHandle craftHandle;        // CraftEntity#getHandle()
    private final Field connectionField;           // ServerPlayer#connection
    private final Field networkField;              // ServerCommonPacketListenerImpl#connection (a Connection)
    private final Field channelField;              // Connection#channel
    private final MethodHandle send;               // ServerCommonPacketListenerImpl#send(Packet)
    private final MethodHandle sendListening;      // ...#send(Packet, ChannelFutureListener)
    private final MethodHandle newMapId;           // new MapId(int)
    private final MethodHandle newMapPatch;        // new MapItemSavedData.MapPatch(x, y, w, h, bytes)
    private final MethodHandle newMapPacket;       // new ClientboundMapItemDataPacket(id, scale, locked, Optional, Optional)
    private final MethodHandle newCameraPacket;    // new ClientboundSetCameraPacket(Entity)
    private final MethodHandle newPingPacket;      // new ClientboundPingPacket(int)
    private final Class<?> pongClass;
    private final MethodHandle pongId;
    private final MethodHandle mapColor;           // MapColor.getColorFromPackedId(int)
    final String packetHandlerName;                // HandlerNames.PACKET_HANDLER

    // The inventory packets the conceal filter rewrites.
    final Class<?> contentPacket;
    final Field contentContainer, contentState, contentItems, contentCarried;
    final MethodHandle newContentPacket;
    final Class<?> slotPacket;
    final Field slotContainer, slotState, slotSlot;
    final MethodHandle newSlotPacket;
    final Class<?> inventoryPacket;
    final Field inventorySlot;
    final MethodHandle newInventoryPacket;
    final Object emptyItem;
    final Field containerMenu;                     // Player#containerMenu
    final Field menuContainerId, menuSlots;

    public Nms(org.bukkit.Server server) throws ReflectiveOperationException {
        String craft = server.getClass().getPackageName();
        craftHandle = L.unreflect(Class.forName(craft + ".entity.CraftEntity").getMethod("getHandle"));
        Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
        connectionField = serverPlayer.getField("connection");
        Class<?> common = Class.forName("net.minecraft.server.network.ServerCommonPacketListenerImpl");
        networkField = common.getField("connection");
        channelField = Class.forName("net.minecraft.network.Connection").getField("channel");
        Class<?> packet = Class.forName("net.minecraft.network.protocol.Packet");
        send = L.unreflect(common.getMethod("send", packet));
        sendListening = L.unreflect(common.getMethod("send", packet, ChannelFutureListener.class));

        Class<?> mapId = Class.forName("net.minecraft.world.level.saveddata.maps.MapId");
        Class<?> patch = Class.forName("net.minecraft.world.level.saveddata.maps.MapItemSavedData$MapPatch");
        newMapId = L.findConstructor(mapId, MethodType.methodType(void.class, int.class));
        newMapPatch = L.findConstructor(patch, MethodType.methodType(void.class, int.class, int.class, int.class, int.class, byte[].class));
        newMapPacket = L.findConstructor(Class.forName("net.minecraft.network.protocol.game.ClientboundMapItemDataPacket"),
                MethodType.methodType(void.class, mapId, byte.class, boolean.class, Optional.class, Optional.class));

        Class<?> nmsEntity = Class.forName("net.minecraft.world.entity.Entity");
        newCameraPacket = L.findConstructor(Class.forName("net.minecraft.network.protocol.game.ClientboundSetCameraPacket"),
                MethodType.methodType(void.class, nmsEntity));
        newPingPacket = L.findConstructor(Class.forName("net.minecraft.network.protocol.common.ClientboundPingPacket"),
                MethodType.methodType(void.class, int.class));
        pongClass = Class.forName("net.minecraft.network.protocol.common.ServerboundPongPacket");
        pongId = L.unreflect(pongClass.getMethod("getId"));
        mapColor = L.unreflect(Class.forName("net.minecraft.world.level.material.MapColor").getMethod("getColorFromPackedId", int.class));
        packetHandlerName = (String) Class.forName("net.minecraft.network.HandlerNames").getField("PACKET_HANDLER").get(null);

        Class<?> item = Class.forName("net.minecraft.world.item.ItemStack");
        emptyItem = item.getField("EMPTY").get(null);
        contentPacket = Class.forName("net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket");
        contentContainer = open(contentPacket, "containerId");
        contentState = open(contentPacket, "stateId");
        contentItems = open(contentPacket, "items");
        contentCarried = open(contentPacket, "carriedItem");
        newContentPacket = L.findConstructor(contentPacket, MethodType.methodType(void.class, int.class, int.class, List.class, item));
        slotPacket = Class.forName("net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket");
        slotContainer = open(slotPacket, "containerId");
        slotState = open(slotPacket, "stateId");
        slotSlot = open(slotPacket, "slot");
        newSlotPacket = L.findConstructor(slotPacket, MethodType.methodType(void.class, int.class, int.class, int.class, item));
        inventoryPacket = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket");
        inventorySlot = open(inventoryPacket, "slot");
        newInventoryPacket = L.findConstructor(inventoryPacket, MethodType.methodType(void.class, int.class, item));
        containerMenu = Class.forName("net.minecraft.world.entity.player.Player").getField("containerMenu");
        Class<?> menu = Class.forName("net.minecraft.world.inventory.AbstractContainerMenu");
        menuContainerId = menu.getField("containerId");
        menuSlots = menu.getField("slots");
    }

    private static Field open(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /** The server's own object behind a Bukkit entity. */
    public Object handle(Entity entity) {
        try {
            return craftHandle.invoke(entity);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private Object listener(Player player) throws IllegalAccessException {
        return connectionField.get(handle(player));
    }

    /** The player's netty channel, or null once they have gone. */
    public Channel channel(Player player) {
        try {
            Object listener = listener(player);
            return listener == null ? null : (Channel) channelField.get(networkField.get(listener));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Sends a server packet object; false if the player has no connection any more. */
    public boolean send(Player player, Object packet) {
        try {
            Object listener = listener(player);
            if (listener == null) return false;
            send.invoke(listener, packet);
            return true;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** [done] runs once netty has written (or failed to write) the packet. */
    public boolean send(Player player, Object packet, ChannelFutureListener done) {
        try {
            Object listener = listener(player);
            if (listener == null) return false;
            sendListening.invoke(listener, packet, done);
            return true;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** A map data packet carrying one rectangle of colour bytes and no decorations. */
    public Object mapPatch(int mapId, int x, int y, int w, int h, byte[] colors) {
        try {
            Object patch = newMapPatch.invoke(x, y, w, h, colors);
            return newMapPacket.invoke(newMapId.invoke(mapId), (byte) 0, true, Optional.empty(), Optional.of(patch));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Lock [player]'s view to [target]. The API only allows this in spectator mode. */
    public void setCamera(Player player, Entity target) {
        try {
            send(player, newCameraPacket.invoke(handle(target)));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    public Object ping(int id) {
        try {
            return newPingPacket.invoke(id);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The id a pong answers, or null if [msg] is not a pong. */
    public Integer pongId(Object msg) {
        if (!pongClass.isInstance(msg)) return null;
        try {
            return (int) pongId.invoke(msg);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** The client's colour for a packed map colour byte, as the client itself computes it (ARGB). */
    public int mapColor(int packedId) {
        try {
            return (int) mapColor.invoke(packedId);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    /** Field read that turns the checked exception into an unchecked one. */
    static Object get(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static Object make(MethodHandle constructor, Object... args) {
        try {
            return constructor.invokeWithArguments(args);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}
