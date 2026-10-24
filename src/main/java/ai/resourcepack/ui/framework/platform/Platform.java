package ai.resourcepack.ui.framework.platform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.chat.ComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.function.IntSupplier;

/**
 * Everything that differs between Spigot and Paper, in one place. The plugin compiles against spigot-api, which
 * Paper implements too, so the rest of the code is the same on both. What is left here is either done the Bukkit
 * way on both (chat, which leaves as BungeeCord components) or done Paper's way where Paper has a method for it,
 * found by name at runtime, and the Spigot way otherwise.
 *
 * <p>Chat is built with Adventure throughout, the plugin's own copy, shaded and relocated: Spigot has none and
 * Paper's must never meet ours. So no Adventure type crosses into the server, only JSON and BungeeCord chat.
 */
public final class Platform {
    private Platform() {}

    private static final MethodHandles.Lookup PUBLIC = MethodHandles.publicLookup();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    /** Paper, or a fork of it: only for saying so. Every difference below is decided by what the server has, not by this. */
    public static final boolean PAPER = present("com.destroystokyo.paper.PaperConfig") || present("io.papermc.paper.configuration.Configuration");

    private static boolean present(String name) {
        try {
            Class.forName(name, false, Platform.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** A public method of [owner] if this server has it, else null. */
    private static MethodHandle virtual(Class<?> owner, String name, MethodType type) {
        try {
            return PUBLIC.findVirtual(owner, name, type);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

    // ------------------------------------------------------------------ the tick

    private static volatile IntSupplier ticks;

    /**
     * The server's tick count. Paper has {@code Bukkit.getCurrentTick()}; Spigot has none in its API, so it is
     * the game's own count, {@code MinecraftServer.getTickCount()}, which goes up at the start of every tick as
     * Paper's does. Only ever compared with itself, so the two need not agree on where they started.
     */
    public static int tick() {
        IntSupplier t = ticks;
        if (t == null) ticks = t = tickSource();
        return t.getAsInt();
    }

    /** For tests, which run with a stand-in server that has neither count. */
    public static void useTicks(IntSupplier source) {
        ticks = source;
    }

    private static IntSupplier tickSource() {
        try {
            MethodHandle paper = PUBLIC.findStatic(Bukkit.class, "getCurrentTick", MethodType.methodType(int.class));
            return () -> {
                try {
                    return (int) paper.invokeExact();
                } catch (Throwable t) {
                    throw rethrow(t);
                }
            };
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
            // Spigot: below.
        }
        try {
            Object server = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            MethodHandle count = PUBLIC.unreflect(server.getClass().getMethod("getTickCount")).bindTo(server);
            return () -> {
                try {
                    return (int) count.invoke();
                } catch (Throwable t) {
                    throw rethrow(t);
                }
            };
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("This server has no tick count MC GUI can read", e);
        }
    }

    // ------------------------------------------------------------------ chat

    /** [message] as BungeeCord chat, which Spigot and Paper both take, by way of the game's JSON. */
    public static BaseComponent bungee(Component message) {
        return ComponentSerializer.deserialize(GsonComponentSerializer.gson().serialize(message));
    }

    public static void send(CommandSender to, Component message) {
        if (to instanceof Player p) p.spigot().sendMessage(bungee(message));
        // The console takes colour codes, and prints the colours.
        else to.sendMessage(LEGACY.serialize(message));
    }

    public static void actionBar(Player player, Component message) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, bungee(message));
    }

    /** With section-sign colour codes, hex ones included: for the Bukkit methods that take only a string. */
    public static String legacy(Component text) {
        return LEGACY.serialize(text);
    }

    public static String plain(Component text) {
        return PlainTextComponentSerializer.plainText().serialize(text);
    }

    /** A player's display name with any colour codes taken out. */
    public static String displayName(Player player) {
        String name = ChatColor.stripColor(player.getDisplayName());
        return name == null ? player.getName() : name;
    }

    /** The server's MOTD, as plain words. */
    public static String motd() {
        String motd = ChatColor.stripColor(Bukkit.getMotd());
        return motd == null ? "" : motd;
    }

    // ------------------------------------------------------------------ text displays

    private static volatile MethodHandle fromJson, setText;
    private static volatile boolean textByJson = true;

    /**
     * Sets a text display's text. Spigot's {@code setText} takes a legacy string, which has no way to name a font,
     * and the pointer is a glyph in the pack's own font; so the text goes in as the game's own component, made
     * from JSON ({@code CraftChatMessage.fromJSON}) and set on the entity behind the display. Both servers have
     * those. Should a server not, the legacy string, which loses only the font.
     */
    public static void text(TextDisplay display, Component text) {
        if (textByJson) {
            try {
                if (setText == null) {
                    Class<?> craft = Class.forName(Bukkit.getServer().getClass().getPackageName() + ".util.CraftChatMessage");
                    Class<?> nmsComponent = Class.forName("net.minecraft.network.chat.Component");
                    Object handle = display.getClass().getMethod("getHandle").invoke(display);
                    fromJson = PUBLIC.unreflect(craft.getMethod("fromJSON", String.class));
                    setText = PUBLIC.unreflect(handle.getClass().getMethod("setText", nmsComponent));
                }
                Object handle = display.getClass().getMethod("getHandle").invoke(display);
                setText.invoke(handle, fromJson.invoke(GsonComponentSerializer.gson().serialize(text)));
                return;
            } catch (Throwable t) {
                textByJson = false;
                Bukkit.getLogger().warning("[MCGUI] Text displays get legacy text on this server, without their font: " + t);
            }
        }
        display.setText(LEGACY.serialize(text));
    }

    // ------------------------------------------------------------------ the server

    /** The server's command map: {@code getCommandMap()} is on Paper's Server, and on both servers' CraftServer. */
    public static CommandMap commandMap() {
        try {
            return (CommandMap) Bukkit.getServer().getClass().getMethod("getCommandMap").invoke(Bukkit.getServer());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot reach the server's command map", e);
        }
    }

    private static final MethodHandle PROTOCOL = virtual(Player.class, "getProtocolVersion", MethodType.methodType(int.class));
    private static volatile int serverProtocol = -1;

    /**
     * The protocol version [player]'s client connected with. Paper keeps the handshake's; Spigot does not, and
     * lets in only its own version, so there it is the server's ({@code SharedConstants.getProtocolVersion()}).
     */
    public static int protocol(Player player) {
        if (PROTOCOL != null) {
            try {
                return (int) PROTOCOL.invoke(player);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }
        if (serverProtocol < 0) {
            try {
                serverProtocol = (int) Class.forName("net.minecraft.SharedConstants").getMethod("getProtocolVersion").invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read the server's protocol version", e);
            }
        }
        return serverProtocol;
    }

    /**
     * An online player's profile, skin and all. Called by name: Paper's {@code getPlayerProfile()} returns its own
     * profile type, a different method to the JVM from Spigot's, though the object is a Bukkit profile on both.
     */
    public static PlayerProfile profile(Player player) {
        try {
            return (PlayerProfile) player.getClass().getMethod("getPlayerProfile").invoke(player);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ the world

    /** The face of the block [player] looks at within [range], fluids looked through; null if none. */
    public static BlockFace targetFace(Player player, int range) {
        RayTraceResult hit = player.rayTraceBlocks(range, FluidCollisionMode.NEVER);
        return hit == null || hit.getHitBlock() == null ? null : hit.getHitBlockFace();
    }

    private static final MethodHandle HAS_COLLISIONS = virtual(World.class, "hasCollisionsIn", MethodType.methodType(boolean.class, BoundingBox.class));

    /**
     * Whether [box] runs into anything solid in [world]. Paper's {@code hasCollisionsIn} where there is one (blocks,
     * the world border and the few entities that are solid, a boat or a shulker); otherwise the blocks' own
     * collision shapes and the border, which is all of it but those entities.
     */
    public static boolean collides(World world, BoundingBox box) {
        if (HAS_COLLISIONS != null) {
            try {
                return (boolean) HAS_COLLISIONS.invoke(world, box);
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }
        WorldBorder border = world.getWorldBorder();
        if (!border.isInside(new org.bukkit.Location(world, box.getMinX(), box.getMinY(), box.getMinZ()))
                || !border.isInside(new org.bukkit.Location(world, box.getMaxX(), box.getMaxY(), box.getMaxZ()))) return true;
        // From a block below: a fence or a wall reaches half a block above its own.
        for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX()); x++) {
            for (int y = (int) Math.floor(box.getMinY()) - 1; y <= (int) Math.floor(box.getMaxY()); y++) {
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ()); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType().isAir()) continue;
                    for (BoundingBox part : block.getCollisionShape().getBoundingBoxes()) {
                        if (part.shift(x, y, z).overlaps(box)) return true;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ attributes

    private static final MethodHandle ADD_TRANSIENT = virtual(AttributeInstance.class, "addTransientModifier",
            MethodType.methodType(void.class, AttributeModifier.class));

    /**
     * Adds [modifier] so that it is never saved, where the server can (Paper); Spigot saves every modifier, so
     * there whoever adds one must also take it off on join, after a crash.
     */
    public static void addTransient(AttributeInstance attribute, AttributeModifier modifier) {
        if (ADD_TRANSIENT != null) {
            try {
                ADD_TRANSIENT.invoke(attribute, modifier);
                return;
            } catch (Throwable t) {
                throw rethrow(t);
            }
        }
        attribute.addModifier(modifier);
    }

    /** Takes every modifier under [key] off [attribute]; nothing happens if it has none. */
    public static void removeModifier(AttributeInstance attribute, NamespacedKey key) {
        for (AttributeModifier m : new ArrayList<>(attribute.getModifiers())) {
            if (key.equals(m.getKey())) attribute.removeModifier(m);
        }
    }
}
