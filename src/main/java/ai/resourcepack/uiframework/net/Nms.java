package ai.resourcepack.uiframework.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

/**
 * Everything UI Framework needs that the Paper API cannot do, reached by reflection on the
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
    private final MethodHandle accepting;          // ServerGamePacketListenerImpl#isAcceptingMessages()
    private final Executor serverThread;           // the MinecraftServer, which runs tasks between ticks
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

    // A seated player's mouse, read off the wire, and the pointer moves sent straight back.
    private final Class<?> movePacket;             // ServerboundMovePlayerPacket
    private final Field moveYRot, moveXRot, moveHasRot;
    private final Class<?> acceptTeleportPacket;   // ServerboundAcceptTeleportationPacket
    private final MethodHandle newTeleport;        // new ClientboundTeleportEntityPacket(id, PositionMoveRotation, Set<Relative>, onGround)
    private final MethodHandle newPositionMoveRotation;
    private final MethodHandle newVec3;
    private final MethodHandle newPlayerRotation;  // new ClientboundPlayerRotationPacket(yRot, relativeY, xRot, relativeX)
    private final Class<?> moveEntityPacket;       // ClientboundMoveEntityPacket and its Pos/PosRot/Rot
    private final Field moveEntityId;
    private final Class<?> positionSyncPacket, teleportPacket;
    private final MethodHandle positionSyncId, teleportId;
    /** What a click or a wheel step sends, each before that client tick's rotation. */
    private final List<Class<?>> clickPackets;
    /** What the client sends at the end of each of its ticks, after that tick's clicks and rotation. */
    private final Class<?> tickEndPacket;          // ServerboundClientTickEndPacket
    // A held player: flying, as far as their client knows, at a speed of nothing (see heldAbilities).
    private final Class<?> abilitiesPacket;        // ClientboundPlayerAbilitiesPacket
    private final MethodHandle abilitiesInvulnerable, abilitiesInstabuild;
    private final MethodHandle newAbilitiesPacket; // new ClientboundPlayerAbilitiesPacket(Abilities)
    private final MethodHandle newAbilities;       // new Abilities()
    private final Field abilityFlying, abilityMayfly, abilityInvulnerable, abilityInstabuild;
    private final MethodHandle setFlyingSpeed, setWalkingSpeed;
    private final MethodHandle onUpdateAbilities; // Player#onUpdateAbilities(), which the server player answers by sending them
    private final MethodHandle playerTeleport;     // ServerGamePacketListenerImpl#teleport(PositionMoveRotation, Set<Relative>)
    private final java.util.Set<?> keepRotation;   // Relative.X_ROT and Y_ROT: a teleport that leaves the rotation alone

    // Frames pointed at other maps for one player: the item a frame shows, and the packets it travels in.
    private final Class<?> bundlePacket;           // ClientboundBundlePacket
    private final MethodHandle newBundle;          // new ClientboundBundlePacket(Iterable)
    private final MethodHandle subPackets;         // BundlePacket#subPackets()
    private final Class<?> addEntityPacket;        // ClientboundAddEntityPacket
    private final MethodHandle addEntityId;
    private final Class<?> dataPacket;             // ClientboundSetEntityDataPacket(int id, List<DataValue<?>>)
    private final MethodHandle dataPacketId, dataPacketItems, newDataPacket;
    private final MethodHandle dataValueId;        // SynchedEntityData.DataValue#id()
    private final MethodHandle dataValue;          // DataValue.create(EntityDataAccessor, Object)
    private final Object frameItem;                // ItemFrame.DATA_ITEM
    private final int frameItemId;
    private final MethodHandle newItemStack;       // new ItemStack(ItemLike)
    private final Object filledMap;                // Items.FILLED_MAP
    private final Object mapIdComponent;           // DataComponents.MAP_ID
    private final MethodHandle setComponent;       // ItemStack#set(DataComponentType, Object)
    // Frames that exist only on one client (a view's wall): the packets the tracker would have sent for them.
    private final MethodHandle newAddEntity;       // new ClientboundAddEntityPacket(id, uuid, x, y, z, xRot, yRot, type, data, movement, yHeadRot)
    private final Object glowFrameType;            // EntityType glow_item_frame
    private final Object noMovement;               // Vec3.ZERO
    private final Object sharedFlags;              // Entity.DATA_SHARED_FLAGS_ID
    private final Object silent;                   // Entity.DATA_SILENT
    private final MethodHandle newRemoveEntities;  // new ClientboundRemoveEntitiesPacket(int...)
    private final MethodHandle nextEntityId;       // UnsafeValues#nextEntityId(World) (26.x) or #nextEntityId() (1.21)
    private final boolean nextEntityIdTakesWorld;
    /** A filled map item for each map id a frame has been pointed at; never changed once made. */
    private final Map<Integer, Object> mapItems = new ConcurrentHashMap<>();

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
        accepting = L.unreflect(Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl").getMethod("isAcceptingMessages"));
        serverThread = (Executor) server.getClass().getMethod("getServer").invoke(server);

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

        String game = "net.minecraft.network.protocol.game.";
        movePacket = Class.forName(game + "ServerboundMovePlayerPacket");
        moveYRot = open(movePacket, "yRot");
        moveXRot = open(movePacket, "xRot");
        moveHasRot = open(movePacket, "hasRot");
        acceptTeleportPacket = Class.forName(game + "ServerboundAcceptTeleportationPacket");
        Class<?> vec3 = Class.forName("net.minecraft.world.phys.Vec3");
        Class<?> pmr = Class.forName("net.minecraft.world.entity.PositionMoveRotation");
        newVec3 = L.findConstructor(vec3, MethodType.methodType(void.class, double.class, double.class, double.class));
        newPositionMoveRotation = L.findConstructor(pmr, MethodType.methodType(void.class, vec3, vec3, float.class, float.class));
        teleportPacket = Class.forName(game + "ClientboundTeleportEntityPacket");
        newTeleport = L.findConstructor(teleportPacket, MethodType.methodType(void.class, int.class, pmr, java.util.Set.class, boolean.class));
        teleportId = L.unreflect(teleportPacket.getMethod("id"));
        positionSyncPacket = Class.forName(game + "ClientboundEntityPositionSyncPacket");
        positionSyncId = L.unreflect(positionSyncPacket.getMethod("id"));
        moveEntityPacket = Class.forName(game + "ClientboundMoveEntityPacket");
        moveEntityId = open(moveEntityPacket, "entityId");
        newPlayerRotation = L.findConstructor(Class.forName(game + "ClientboundPlayerRotationPacket"),
                MethodType.methodType(void.class, float.class, boolean.class, float.class, boolean.class));

        bundlePacket = Class.forName(game + "ClientboundBundlePacket");
        newBundle = L.findConstructor(bundlePacket, MethodType.methodType(void.class, Iterable.class));
        subPackets = L.unreflect(Class.forName("net.minecraft.network.protocol.BundlePacket").getMethod("subPackets"));
        addEntityPacket = Class.forName(game + "ClientboundAddEntityPacket");
        addEntityId = L.unreflect(addEntityPacket.getMethod("getId"));
        dataPacket = Class.forName(game + "ClientboundSetEntityDataPacket");
        dataPacketId = L.unreflect(dataPacket.getMethod("id"));
        dataPacketItems = L.unreflect(dataPacket.getMethod("packedItems"));
        newDataPacket = L.findConstructor(dataPacket, MethodType.methodType(void.class, int.class, List.class));
        Class<?> value = Class.forName("net.minecraft.network.syncher.SynchedEntityData$DataValue");
        Class<?> accessor = Class.forName("net.minecraft.network.syncher.EntityDataAccessor");
        dataValueId = L.unreflect(value.getMethod("id"));
        dataValue = L.unreflect(value.getMethod("create", accessor, Object.class));
        frameItem = open(Class.forName("net.minecraft.world.entity.decoration.ItemFrame"), "DATA_ITEM").get(null);
        frameItemId = (int) accessor.getMethod("id").invoke(frameItem);
        newItemStack = L.findConstructor(item, MethodType.methodType(void.class, Class.forName("net.minecraft.world.level.ItemLike")));
        filledMap = Class.forName("net.minecraft.world.item.Items").getField("FILLED_MAP").get(null);
        Class<?> componentType = Class.forName("net.minecraft.core.component.DataComponentType");
        mapIdComponent = Class.forName("net.minecraft.core.component.DataComponents").getField("MAP_ID").get(null);
        setComponent = L.unreflect(item.getMethod("set", componentType, Object.class));
        Class<?> entityType = Class.forName("net.minecraft.world.entity.EntityType");
        Class<?> types;
        try {
            types = Class.forName("net.minecraft.world.entity.EntityTypes"); // 26.x keeps the constants here
        } catch (ClassNotFoundException e) {
            types = entityType;
        }
        glowFrameType = types.getField("GLOW_ITEM_FRAME").get(null);
        newAddEntity = L.findConstructor(addEntityPacket, MethodType.methodType(void.class, int.class, java.util.UUID.class,
                double.class, double.class, double.class, float.class, float.class, entityType, int.class, vec3, double.class));
        noMovement = vec3.getField("ZERO").get(null);
        sharedFlags = open(nmsEntity, "DATA_SHARED_FLAGS_ID").get(null);
        silent = open(nmsEntity, "DATA_SILENT").get(null);
        newRemoveEntities = L.findConstructor(Class.forName(game + "ClientboundRemoveEntitiesPacket"), MethodType.methodType(void.class, int[].class)).asFixedArity();
        Object unsafe = server.getUnsafe();
        MethodHandle next;
        boolean takesWorld;
        try {
            next = L.unreflect(unsafe.getClass().getMethod("nextEntityId", org.bukkit.World.class)).bindTo(unsafe);
            takesWorld = true;
        } catch (NoSuchMethodException e) {
            next = L.unreflect(unsafe.getClass().getMethod("nextEntityId")).bindTo(unsafe);
            takesWorld = false;
        }
        nextEntityId = next;
        nextEntityIdTakesWorld = takesWorld;
        // Looked up leniently: a click type a version lacks (or renamed) only loses its exact timing.
        List<Class<?>> clicks = new java.util.ArrayList<>();
        for (String name : List.of("ServerboundSwingPacket", "ServerboundAttackPacket", "ServerboundInteractPacket",
                "ServerboundUseItemOnPacket", "ServerboundUseItemPacket", "ServerboundSetCarriedItemPacket")) {
            try {
                clicks.add(Class.forName(game + name));
            } catch (ClassNotFoundException ignored) {
                // See above.
            }
        }
        clickPackets = List.copyOf(clicks);
        tickEndPacket = Class.forName(game + "ServerboundClientTickEndPacket");
        abilitiesPacket = Class.forName(game + "ClientboundPlayerAbilitiesPacket");
        abilitiesInvulnerable = L.unreflect(abilitiesPacket.getMethod("isInvulnerable"));
        abilitiesInstabuild = L.unreflect(abilitiesPacket.getMethod("canInstabuild"));
        Class<?> abilities = Class.forName("net.minecraft.world.entity.player.Abilities");
        newAbilitiesPacket = L.findConstructor(abilitiesPacket, MethodType.methodType(void.class, abilities));
        newAbilities = L.findConstructor(abilities, MethodType.methodType(void.class));
        abilityFlying = abilities.getField("flying");
        abilityMayfly = abilities.getField("mayfly");
        abilityInvulnerable = abilities.getField("invulnerable");
        abilityInstabuild = abilities.getField("instabuild");
        setFlyingSpeed = L.unreflect(abilities.getMethod("setFlyingSpeed", float.class));
        setWalkingSpeed = L.unreflect(abilities.getMethod("setWalkingSpeed", float.class));
        onUpdateAbilities = L.unreflect(Class.forName("net.minecraft.world.entity.player.Player").getMethod("onUpdateAbilities"));
        Class<?> relative = Class.forName("net.minecraft.world.entity.Relative");
        playerTeleport = L.unreflect(Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl")
                .getMethod("teleport", pmr, java.util.Set.class));
        keepRotation = java.util.Set.of(enumConstant(relative, "X_ROT"), enumConstant(relative, "Y_ROT"));
    }

    private static Field open(Class<?> owner, String name) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Object enumConstant(Class<?> type, String name) throws NoSuchFieldException, IllegalAccessException {
        return type.getField(name).get(null);
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

    /**
     * Writes [packets] to [player] in order with one flush, from any thread, without waiting for the
     * server thread: {@link #send} off the server thread only queues a packet until that thread next
     * drains the queue, up to a tick later. The write happens on the connection's own thread, and is
     * dropped once the connection has started switching away from play (reconfiguration), where a game
     * packet would fail to encode and take the player down. [done] hears whether the last packet was
     * written (false if dropped or failed). False, and [done] never called, if the player has no
     * connection.
     */
    public boolean writeNow(Player player, List<?> packets, Consumer<Boolean> done) {
        if (packets.isEmpty()) return true;
        Object listener;
        Channel channel;
        try {
            listener = listener(player);
            if (listener == null) return false;
            channel = (Channel) channelField.get(networkField.get(listener));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
        if (channel == null || !channel.isActive()) return false;
        try {
            channel.eventLoop().execute(() -> {
                ChannelFuture last;
                try {
                    if (!channel.isActive() || !accepting(listener)) {
                        done.accept(false);
                        return;
                    }
                    last = null;
                    for (Object p : packets) last = channel.write(p);
                    channel.flush();
                } catch (RuntimeException e) {
                    // [done] must always run, or the bytes stay counted as in flight and the player's pictures stall.
                    done.accept(false);
                    return;
                }
                last.addListener(f -> done.accept(f.isSuccess()));
            });
        } catch (RejectedExecutionException e) {
            return false; // the event loop is shutting down with the server
        }
        return true;
    }

    private boolean accepting(Object listener) {
        try {
            return (boolean) accepting.invoke(listener);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * The server thread, as packets reach it: a task runs as soon as the thread is free, which between
     * ticks is at once (the thread is woken for it), rather than at the start of the next tick as the
     * Bukkit scheduler would run it.
     */
    public Executor serverThread() {
        return serverThread;
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

    /** One bundle of [packets]: the client handles all of them in one go, between two frames. */
    public Object bundle(List<?> packets) {
        return make(newBundle, packets);
    }

    /** The packet that makes item frame [entityId] show map [mapId]. */
    public Object frameShows(int entityId, int mapId) {
        return make(newDataPacket, entityId, List.of(make(dataValue, frameItem, mapItem(mapId))));
    }

    /** A fresh entity id from the server's own counter, which no real entity will ever be given. */
    public int nextEntityId(org.bukkit.World world) {
        try {
            return nextEntityIdTakesWorld ? (int) nextEntityId.invoke(world) : (int) nextEntityId.invoke();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * The two packets that bring an invisible, silent glow item frame showing map [mapId] to one client, as
     * the tracker sends a real one ({@code ItemFrame.getAddEntityPacket}, then its non-default data): the
     * frame hangs in block (x, y, z) on [face]'s side.
     */
    public List<Object> frameAppears(int entityId, java.util.UUID uuid, int x, int y, int z, org.bukkit.block.BlockFace face, int mapId) {
        Object add = make(newAddEntity, entityId, uuid, (double) x, (double) y, (double) z, 0f, 0f, glowFrameType, direction3d(face), noMovement, 0d);
        Object data = make(newDataPacket, entityId, List.of(
                make(dataValue, sharedFlags, (byte) (1 << 5)), // Entity.FLAG_INVISIBLE
                make(dataValue, silent, true),
                make(dataValue, frameItem, mapItem(mapId))));
        return List.of(add, data);
    }

    /** Direction.get3DDataValue for a block face: down, up, north, south, west, east. */
    private static int direction3d(org.bukkit.block.BlockFace face) {
        return switch (face) {
            case DOWN -> 0;
            case UP -> 1;
            case NORTH -> 2;
            case SOUTH -> 3;
            case WEST -> 4;
            case EAST -> 5;
            default -> throw new IllegalArgumentException("A frame hangs on a block's side, not " + face);
        };
    }

    /** The packet that takes entities [ids] off a client. */
    public Object removeEntities(int[] ids) {
        try {
            return newRemoveEntities.invoke(ids);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private Object mapItem(int mapId) {
        return mapItems.computeIfAbsent(mapId, id -> {
            try {
                Object stack = newItemStack.invoke(filledMap);
                setComponent.invoke(stack, mapIdComponent, newMapId.invoke(id));
                return stack;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        });
    }

    /**
     * [msg] with every item frame it brings to a client afresh showing the map [mapOf] gives for the frame's entity
     * id (when that is not -1), or [msg] itself if none changes. A frame comes afresh in a bundle with the packet
     * that adds it (spawned, shown again, resent by Paper after a cancelled click), or, never in practice for the
     * engine's fixed frames, in a data packet of its own. A bundle with no new entity in it is left alone: that is
     * how the sender's own re-pointing (never with a new entity) passes untouched.
     */
    public Object pointFrames(Object msg, IntUnaryOperator mapOf) {
        try {
            if (dataPacket.isInstance(msg)) return pointFrame(msg, mapOf);
            if (!bundlePacket.isInstance(msg)) return msg;
            Iterable<?> subs = (Iterable<?>) subPackets.invoke(msg);
            Set<Integer> added = new HashSet<>();
            for (Object p : subs) if (addEntityPacket.isInstance(p)) added.add((int) addEntityId.invoke(p));
            if (added.isEmpty()) return msg;
            List<Object> out = new ArrayList<>();
            boolean changed = false;
            for (Object p : subs) {
                Object q = dataPacket.isInstance(p) && added.contains((int) dataPacketId.invoke(p)) ? pointFrame(p, mapOf) : p;
                changed |= q != p;
                out.add(q);
            }
            return changed ? newBundle.invoke(out) : msg;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private Object pointFrame(Object packet, IntUnaryOperator mapOf) throws Throwable {
        int entity = (int) dataPacketId.invoke(packet);
        int map = mapOf.applyAsInt(entity);
        if (map < 0) return packet;
        List<?> items = (List<?>) dataPacketItems.invoke(packet);
        List<Object> out = new ArrayList<>(items.size());
        boolean found = false;
        for (Object v : items) {
            if ((int) dataValueId.invoke(v) == frameItemId) {
                out.add(dataValue.invoke(frameItem, mapItem(map)));
                found = true;
            } else {
                out.add(v);
            }
        }
        return found ? newDataPacket.invoke(entity, out) : packet;
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

    /** {yaw, pitch} if [msg] is a player turning (a move packet with a rotation), else null. */
    public float[] rotation(Object msg) {
        if (!movePacket.isInstance(msg) || !(boolean) get(moveHasRot, msg)) return null;
        return new float[]{(float) get(moveYRot, msg), (float) get(moveXRot, msg)};
    }

    /** Whether [msg] is the client saying it has applied a teleport (a player position packet) the server sent. */
    public boolean acceptsTeleport(Object msg) {
        return acceptTeleportPacket.isInstance(msg);
    }

    /** The entity a position packet moves, or -1 if [msg] is not one. */
    public int movedEntity(Object msg) {
        try {
            if (moveEntityPacket.isInstance(msg)) return (int) moveEntityId.get(msg);
            if (positionSyncPacket.isInstance(msg)) return (int) positionSyncId.invoke(msg);
            if (teleportPacket.isInstance(msg)) return (int) teleportId.invoke(msg);
            return -1;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Puts entity [id] at (x, y, z), gliding there over its teleport duration. */
    public Object teleport(int id, double x, double y, double z) {
        try {
            Object pmr = newPositionMoveRotation.invoke(newVec3.invoke(x, y, z), newVec3.invoke(0.0, 0.0, 0.0), 0f, 0f);
            return newTeleport.invoke(id, pmr, java.util.Set.of(), false);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Sets a player's pitch and leaves their yaw alone (a relative turn of nothing). */
    public Object pitch(float pitch) {
        return rotate(0f, true, pitch, false);
    }

    /** Turns a player to [yaw] and [pitch], each by that much instead where [relativeYaw] / [relativePitch]. */
    public Object rotate(float yaw, boolean relativeYaw, float pitch, boolean relativePitch) {
        try {
            return newPlayerRotation.invoke(yaw, relativeYaw, pitch, relativePitch);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * Whether [msg] is a click (an attack, a swing, a use, an entity interaction) or a wheel step: the
     * client sends these while it handles input, before the same tick's rotation.
     */
    public boolean isClick(Object msg) {
        for (Class<?> c : clickPackets) if (c.isInstance(msg)) return true;
        return false;
    }

    /** Whether [msg] is the client saying one of its ticks has ended (sent after that tick's clicks and rotation). */
    public boolean isTickEnd(Object msg) {
        return tickEndPacket.isInstance(msg);
    }

    /** Whether [msg] is the server telling a player what they may do: fly, build, take damage. */
    boolean isAbilities(Object msg) {
        return abilitiesPacket.isInstance(msg);
    }

    /**
     * [abilities] (a player abilities packet) as a held player's client is told them: flying, unable to
     * stop flying, and at a flying and walking speed of nothing. Invulnerability and instant building are
     * left as they are.
     */
    Object heldAbilities(Object abilities) {
        try {
            Object a = newAbilities.invoke();
            abilityInvulnerable.set(a, (boolean) abilitiesInvulnerable.invoke(abilities));
            abilityInstabuild.set(a, (boolean) abilitiesInstabuild.invoke(abilities));
            abilityFlying.set(a, true);
            abilityMayfly.set(a, false);
            setFlyingSpeed.invoke(a, 0f);
            setWalkingSpeed.invoke(a, 0f);
            return newAbilitiesPacket.invoke(a);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Sends [player] their abilities again (through the connection's filters, like any other packet). */
    public void resendAbilities(Player player) {
        try {
            onUpdateAbilities.invoke(handle(player));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** Teleports [player] to (x, y, z) without touching their rotation (the client keeps its own). */
    public void teleportKeepingRotation(Player player, double x, double y, double z) {
        try {
            Object listener = listener(player);
            if (listener == null) return;
            Object pmr = newPositionMoveRotation.invoke(newVec3.invoke(x, y, z), newVec3.invoke(0.0, 0.0, 0.0), 0f, 0f);
            playerTeleport.invoke(listener, pmr, keepRotation);
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
