package ai.resourcepack.ui.framework.world;

import ai.resourcepack.ui.framework.platform.Platform;
import ai.resourcepack.ui.framework.Settings;
import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.framework.api.Click;
import ai.resourcepack.ui.framework.api.CloseReason;
import ai.resourcepack.ui.framework.api.HoverLayers;
import ai.resourcepack.ui.framework.api.Key;
import ai.resourcepack.ui.framework.api.Pointer;
import ai.resourcepack.ui.framework.api.View;
import ai.resourcepack.ui.framework.api.ViewContent;
import ai.resourcepack.ui.framework.api.ViewOptions;
import ai.resourcepack.ui.framework.net.ClientVersion;
import ai.resourcepack.ui.framework.net.MapSender;
import ai.resourcepack.ui.framework.net.Nms;
import ai.resourcepack.ui.framework.net.Pipeline;
import ai.resourcepack.ui.framework.pack.ResourcePack;
import ai.resourcepack.ui.framework.pack.Shaders;
import ai.resourcepack.ui.framework.render.Palette;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WeatherType;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Fullscreen views. A seated player's camera looks at a wall of maps only they can see; turning the
 * mouse moves a pointer, the attack and use keys click, the wheel scrolls (the hotbar snaps back),
 * movement keys are keys, and sneaking gets up.
 *
 * <p>With the pack and {@code views.client-pointer}, the player's own eye is the camera and their
 * client places the picture and the pointer itself, every frame, from where it looks (see
 * {@link Shaders}). They are held still where their eye is on the flagged spot: not riding anything
 * (a passenger's yaw reaches the screen a tick late), but flying, as far as their client knows, at a
 * speed of nothing, with attribute modifiers that stop walking, jumping, falling and knockback (see
 * {@link #hold}). Everyone else rides an invisible armour stand with their camera locked onto an
 * invisible mannequin looking at the wall; their rotation moves a pointer the server places.
 *
 * <p>An overlay ({@code ViewOptions.overlay}) with the pack keeps the player where they are: their eye moves at
 * most 1/32 of a block (to the flag on all three axes) and keeps its rotation, so the view does not jump. Their
 * client takes the world as it is from the next frames, keeps it, and shows it round the panel, still, however
 * their camera then turns with the client-placed pointer ({@code Shaders.overlayChain}, see take and keep). Only
 * the maps the panel covers are sent and drawn. Closing puts them back exactly where they were.
 *
 * <p>Players with the pack see the server-placed pointer as a text display in front of the picture,
 * which their client glides between updates; players without it get it drawn into the maps. The mouse
 * is read off the wire as each packet arrives and the pointer's move goes straight back, rather than
 * both waiting for the next tick.
 *
 * <p>With the pack the shader stretches the picture to fill the window, so a server-placed view is
 * placed to fill only part of the player's view: every frame then stays on screen even for a narrower
 * FOV than they told us, and a frame the client thinks is off screen is never drawn at all.
 */
public final class Views implements Listener {
    /** A standing player's eye height, which a mannequin shares. */
    private static final double EYE_HEIGHT = 1.62;
    /** Blocks per glyph pixel of a text display at scale 1 (a display draws 40 to a block). */
    private static final float GLYPH_PX = 0.025f;
    /** A glyph's top is 10 glyph pixels above the text display's origin (measured). */
    private static final float TOP_Y = -10f;
    /** Ticks to wait for a view's picture before switching to it regardless. */
    private static final int REVEAL_TIMEOUT = 60;
    /** How much of the window's height (at the player's FOV) a view is placed to fill when the shader stretches it. */
    private static final double STRETCHED_FIT = 0.6;
    /** Pitch beyond which the player's head is put back level, so the client's clamp at ±90° never stops the pointer. */
    private static final float PITCH_LIMIT = 60;
    /** Ticks to wait for the client to take the teleport to the seat before reading the mouse regardless. */
    private static final int SETTLE_TIMEOUT = 20;
    /**
     * Size of the Interaction round a client-placed pointer's eye, in blocks: its far side (where a look
     * from inside hits it) is nearer than anything a look can hit in the eye's own block, which is air
     * (see clientEye), so a click hits it whichever way the player looks.
     */
    private static final float CLIENT_TARGET = 0.02f;
    /** The widest turn either side of the picture's centre a client-placed pointer may need: below ±180°. */
    private static final double CLIENT_MAX_YAW = 150;
    /** The steepest: below the client's own ±90° limit, with room for a fast flick past the edge. */
    private static final double CLIENT_MAX_PITCH = 75;
    /** How far a client-placed view may move the player's eye up or down to find room to hold them, in blocks. */
    private static final double CLIENT_MAX_LIFT = 1.5;
    /** How far round a held player other entities are hidden from them, in blocks: their client would push them. */
    private static final double PUSH_MARGIN = 2.0;
    /** Pixels across the screen a desktop pointer at Windows' default speed crosses in as many mouse counts: views.pointer-speed 1. */
    private static final double DESKTOP_WIDTH = 1920;
    /**
     * Ticks a kept overlay's client takes the world for (see take) after answering the ping sent with the signature:
     * a new entity, the glowing switch, is first drawn a frame after it arrives, and the panel must not come before
     * the world has been taken without it.
     */
    private static final int KEEP_AFTER = 2;
    /** Ticks after which a kept overlay is kept regardless, if its client never answers. */
    private static final int KEEP_TIMEOUT = 20;
    /** Map ids every view shares, from map-id-base (each player only ever sees their own view's frames): its own maps, then its hover layers'. */
    public static final int VIEW_ID_SPAN = 1024;

    private final Plugin plugin;
    private final Settings settings;
    private final Nms nms;
    private final Pipeline pipeline;
    private final Predicate<UUID> hasPack;
    private final int firstMapId;
    private final MapSender sender;
    private final Palette palette;
    private final Map<UUID, ViewImpl> views = new ConcurrentHashMap<>();
    private final NamespacedKey seatKey, returnKey, fovKey, stepKey, pointerKey;
    /** What each online player's mouse turns them by a count, being learned (server thread). */
    private final Map<UUID, MouseSteps> mice = new HashMap<>();
    private final EntityType mannequin;

    public Views(Plugin plugin, Settings settings, Nms nms, Pipeline pipeline, Predicate<UUID> hasPack, MapSender sender, Palette palette, int firstMapId) {
        this.plugin = plugin;
        this.settings = settings;
        this.nms = nms;
        this.pipeline = pipeline;
        this.hasPack = hasPack;
        this.sender = sender;
        this.palette = palette;
        this.firstMapId = firstMapId;
        this.seatKey = new NamespacedKey(plugin, "seated");
        this.returnKey = new NamespacedKey(plugin, "return-to");
        this.fovKey = new NamespacedKey(plugin, "fov");
        this.stepKey = new NamespacedKey(plugin, "mouse-step");
        this.pointerKey = new NamespacedKey(plugin, "pointer-speed");
        // 1.21.9+: a camera with a player's eye height (see spawnCamera).
        this.mannequin = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft("mannequin"));
    }

    public final class ViewImpl implements View {
        final UUID player;
        /** The hotbar slot the player held before {@link #restSlot} moved it; -1 if it never did. */
        int heldSlot = -1;
        final ViewContent content;
        final Canvas canvas;
        final MapWall wall;
        final ViewOptions options;
        final boolean withPack;
        /** The player's client places the pointer (and the picture) itself, from where their own eye looks. */
        final boolean clientPointer;
        /**
         * An overlay whose client keeps the world round its panel as it was when it opened (see
         * Shaders.overlayChain): a client-placed pointer whose camera is the player's own eye, where it was.
         */
        final boolean keeps;
        /** A kept overlay's switch for that post effect: a glowing display that draws nothing anybody sees. */
        final BlockDisplay glow;
        /**
         * A kept overlay: 0 until it opens, 1 while the client takes the world (the signature sent, no panel yet), 2
         * once it keeps it (the panel shown). [takenSince]: the tick the client was known to be taking it, -1 before.
         */
        int stage;
        volatile int takenSince = -1;
        /** The tick a kept overlay is kept by at the latest, if the client never answers the ping. */
        int keepBy;
        /** A kept overlay's turn to the picture's axis, sent with the switch to keeping, until the client has answered the ping after it. */
        volatile boolean aligning;
        /** The tint and flags the signature map was last sent with. */
        int signatureTint, signatureFlags = -1;
        /** The drawing a kept overlay's panel was last worked out from (see panel). */
        Canvas.Snapshot panelOf;
        /** The canvas the last assignment showed (the content may hand over another). */
        volatile Canvas shown;
        /** Pixels per degree of turn: for a client-placed pointer, the one the shader is told too. */
        final double speed;
        /** The yaw that looks straight at the picture's centre. */
        final float refYaw;
        /** What a server-placed pointer's player rides; null for a client-placed pointer, whose player rides nothing. */
        final ArmorStand vehicle;
        /** Where a client-placed pointer's player is held: their feet, so their eye is on the flagged spot. */
        final Location seat;
        /** The entity the camera is locked to; null for a client-placed pointer, whose camera is the player's own eye. */
        final LivingEntity camera;
        final Interaction target;
        final TextDisplay pointer;
        final GameMode previousMode;
        final WeatherType previousWeather;
        /** The player's own invisibility (a potion), which the view's effect replaces until they stand. */
        final PotionEffect previousInvisibility;
        final Location returnTo;
        final PrivateView privateView;
        /** Hover layers (see {@link HoverLayers}); null without. */
        final LayerSet layers;
        final int openedAt, deadline;
        final int pointerId;
        /** Written under the view's lock on the netty thread as the mouse moves, read by the server thread. */
        volatile double cursorX, cursorY;
        float lastYaw, lastPitch;
        boolean primed;
        /** Revealed, and the client has not yet taken the teleport to the seat (until [settleBy] at most). */
        volatile boolean awaitingTeleport;
        int settleBy;
        /** A level head has been sent and the client's rotation may still come from before it. */
        volatile boolean pitchReset;
        /** A client-placed pointer: the player's whole turn from the picture's centre, not wrapped (degrees). */
        double turnYaw;
        /** A turn back inside the picture has been sent and the client has not yet answered the ping after it. */
        volatile boolean correcting;
        /**
         * Bumped, on any thread, when the server turns the player and again once their client has taken the
         * turn; [serverTurnsSeen] is what the server thread last saw (see {@link #turnedByServer}).
         */
        final AtomicInteger serverTurns = new AtomicInteger();
        int serverTurnsSeen;
        /**
         * Clicks seen on the wire, and how many of them a rotation has followed (under the view's lock): a
         * client sends a click before the same tick's rotation, so the rotation after it is where the
         * pointer was drawn when it was made. [clickX], [clickY]: the cursor that rotation gave.
         */
        int clicksSeen, clicksPlaced;
        double clickX, clickY;
        /** The cursor when the last click went by, before its rotation (logged with views.debug-pointer). */
        double preClickX, preClickY;
        /** Clicks handled before the rotation after them had arrived; run once it has, or the client's tick has ended without one. */
        final List<java.util.function.BiConsumer<Integer, Integer>> waitingClicks = new ArrayList<>();
        /** The server tick the first of [waitingClicks] began waiting. */
        int waitingSince;
        Input lastInput;
        final Map<Key, Boolean> held = new EnumMap<>(Key.class);
        long lastClickTick = -10, lastRightClickTick = -10;
        Pointer shape = Pointer.ARROW;
        Pointer shownShape = null;
        int lastPointerX = -1, lastPointerY = -1;
        /** The player has moved the pointer off the centre it opens on; until then it is not reported (see report). */
        boolean pointerMoved;
        /** A pointer report is waiting for the server thread (see turned). */
        final java.util.concurrent.atomic.AtomicBoolean reportQueued = new java.util.concurrent.atomic.AtomicBoolean();
        boolean pinged;
        /** The hover layers' {@code changes()} their frames on the client last matched (see frameLayers). */
        int layersFramed;
        volatile boolean revealed, closed;
        volatile boolean caughtUp;

        ViewImpl(Player p, ViewContent content, Canvas canvas, MapWall wall, ViewOptions options, boolean withPack, boolean clientPointer, boolean keeps,
                 double speed, ArmorStand vehicle, Location seat, LivingEntity camera, Interaction target, TextDisplay pointer, BlockDisplay glow,
                 Location returnTo, Location cameraAt, LayerSet layers) {
            this.player = p.getUniqueId();
            this.content = content;
            this.canvas = canvas;
            this.wall = wall;
            this.options = options;
            this.withPack = withPack;
            this.clientPointer = clientPointer;
            this.keeps = keeps;
            this.glow = glow;
            this.shown = canvas;
            this.speed = speed;
            this.refYaw = cameraAt.getYaw();
            this.vehicle = vehicle;
            this.seat = seat;
            this.camera = camera;
            this.target = target;
            this.pointer = pointer;
            this.previousMode = p.getGameMode();
            this.previousWeather = p.getPlayerWeather();
            this.previousInvisibility = p.getPotionEffect(PotionEffectType.INVISIBILITY);
            this.returnTo = returnTo;
            this.privateView = new PrivateView(wall, cameraAt, !withPack, !keeps);
            this.layers = layers;
            this.openedAt = Platform.tick();
            this.deadline = openedAt + REVEAL_TIMEOUT;
            this.pointerId = pointer == null ? -1 : pointer.getEntityId();
            this.cursorX = canvas.width() / 2.0;
            this.cursorY = canvas.height() / 2.0;
            this.clickX = cursorX;
            this.clickY = cursorY;
        }

        List<Entity> entities() {
            List<Entity> out = new ArrayList<>(List.of(target));
            if (vehicle != null) out.add(vehicle);
            if (camera != null) out.add(camera);
            if (pointer != null) out.add(pointer);
            if (glow != null) out.add(glow);
            return out;
        }

        /** Where the player is kept while the view is shown. */
        Location heldAt() {
            return clientPointer ? seat : vehicle.getLocation();
        }

        @Override public Player player() { return Bukkit.getPlayer(player); }
        @Override public ViewContent content() { return content; }
        @Override public int pointerX() { return (int) cursorX; }
        @Override public int pointerY() { return (int) cursorY; }
        @Override public Pointer pointer() { return shape; }
        @Override public void pointer(Pointer shape) { this.shape = shape == null ? Pointer.ARROW : shape; }
        @Override public boolean shown() { return revealed; }
        @Override public HoverLayers layers() { return layers; }

        @Override
        public void restSlot(int slot) {
            Player p = player();
            if (p == null || closed || slot < 0 || slot > 8 || p.getInventory().getHeldItemSlot() == slot) return;
            if (heldSlot < 0) heldSlot = p.getInventory().getHeldItemSlot();
            p.getInventory().setHeldItemSlot(slot);
        }
        @Override public boolean open() { return !closed; }

        @Override
        public void close() {
            Player p = player();
            if (p != null) Views.this.close(p, CloseReason.CLOSED);
        }
    }

    public View viewOf(Player player) {
        return views.get(player.getUniqueId());
    }

    public boolean seated(Player player) {
        return views.containsKey(player.getUniqueId());
    }

    /**
     * Whether [player]'s open view hides placed wall [placed]: it reaches between their camera and the picture, or is
     * level with it (see PrivateView). Placed walls' frames are on clients only, so no entity hiding reaches them.
     */
    public boolean hides(Player player, MapWall placed) {
        ViewImpl v = views.get(player.getUniqueId());
        return v != null && v.privateView.hides(placed);
    }

    /** The player's FOV setting, as they told us with /mcgui fov; the server cannot read it. */
    public int fovOf(Player player) {
        Integer fov = player.getPersistentDataContainer().get(fovKey, PersistentDataType.INTEGER);
        return fov == null ? settings.defaultFov : fov;
    }

    public void setFov(Player player, int fov) {
        player.getPersistentDataContainer().set(fovKey, PersistentDataType.INTEGER, fov);
    }

    /** The field of view a picture is fitted to so it fills [fill] of the window's height. */
    private double viewFov(Player player, double fill) {
        double fov = fovOf(player);
        return fill >= 1 ? fov : Math.toDegrees(2 * Math.atan(fill * Math.tan(Math.toRadians(fov / 2))));
    }

    /**
     * Whether [player]'s client is a version the pack's overlay chain was seen to run in ({@link Shaders.Dialect#of}).
     * Any other may load the pack but not the chain, and then a kept overlay's world would turn with the pointer.
     */
    private boolean runsOverlayChain(Player player) {
        int protocol = ClientVersion.protocol(player);
        Shaders.Dialect d = Shaders.Dialect.of(protocol);
        if (d != null && d.seats()) return true;
        debug(player.getName() + "'s client (protocol " + protocol + ") is no version the overlay chain was checked in");
        return false;
    }

    /**
     * Whether [player]'s client can tell it is seated: a version whose shaders see the camera's position (not 1.21.9
     * to 1.21.10), or one newer than every dialect, which the pack's root shaders are written for.
     */
    private boolean seats(Player player) {
        Shaders.Dialect d = Shaders.Dialect.of(ClientVersion.protocol(player));
        return d == null || d.seats();
    }

    public View open(Player player, ViewContent content, ViewOptions options) {
        close(player, CloseReason.CLOSED);
        Canvas canvas = content.canvas();
        // A client whose shaders cannot see it is seated is shown the view as one without the pack is.
        boolean withPack = hasPack.test(player.getUniqueId()) && seats(player);
        double fov = viewFov(player, withPack ? STRETCHED_FIT : 1.0);
        Location from = player.getLocation();
        // An overlay with the pack keeps the player's own eye as the camera, where it is to within the flag's 1/32 of a
        // block, and their client keeps the world round the panel as it was (see Shaders.overlayChain): so the world
        // neither moves when it opens nor turns with the pointer. Its frames lie at the camera and its maps place
        // themselves in the window, so its wall is only a set of map ids.
        // The signature map takes the id after the tiles', which must stay inside the views' 1024 (VIEW_ID_SPAN).
        Location eye = withPack && settings.clientPointer && options.overlay() && canvas.cols() * canvas.rows() < 1024
                && runsOverlayChain(player) ? overlayEye(player) : null;
        boolean keeps = eye != null;
        MapWall wall = keeps ? new MapWall(player.getWorld(), eye.getBlockX(), eye.getBlockY(), eye.getBlockZ(),
                cardinal(player).getOppositeFace(), canvas.cols(), canvas.rows(), firstMapId) : inFront(player, canvas.cols(), canvas.rows(), fov);
        if (wall == null) throw new IllegalStateException("There isn't enough room around " + player.getName() + " for a view");
        World world = wall.world;
        Location cam = wall.cameraLocation(fov);
        // Where a client-placed picture really is no longer matters, only that the camera is flagged: so a
        // spot by the player's own eye where they can be held (see clientEye). Without one, the server pointer. An
        // overlay with nowhere to hold the player has the server's too (the world shows live round it, still): a
        // client-placed pointer turns the player's own camera, and only a kept world does not turn with it.
        if (!keeps && withPack && settings.clientPointer && !options.overlay()) eye = clientEye(player, wall.facing);
        if (withPack && settings.clientPointer && eye == null) debug("no room to hold " + player.getName() + " still, or no overlay chain: the server places their pointer");
        boolean clientPointer = eye != null;
        Location seat = null;
        if (clientPointer) {
            eye.setYaw(cam.getYaw());
            cam = eye;
            seat = cam.clone().subtract(0, standingEyeHeight(player), 0);
        }
        // On their client only, and not yet: the maps go out first, and the frames only appear once they can show them.
        wall.spawnFor(nms, player, clientPointer ? stackBlock(cam) : null);
        double speed = speedFor(player, canvas, clientPointer);
        LayerSet layers = null;
        // After the tiles' ids and an overlay's signature's (MapWall.signatureMapId).
        int layerMaps = Math.min(settings.hoverLayers, VIEW_ID_SPAN - canvas.tiles() - 1);
        if (clientPointer && layerMaps > 0) {
            // Hover layers carry the speed to the shader in steps: the server's cursor goes by the same.
            speed = Shaders.layerSpeed(speed);
            MapWall row = new MapWall(world, wall.anchorX, wall.anchorY, wall.anchorZ, wall.facing, layerMaps, 1, firstMapId + canvas.tiles() + 1);
            row.spawnFor(nms, player, stackBlock(cam));
            int side = (wall.facing.getModZ() != 0 ? Palette.LAYER_ALONG_Z : 0)
                    | (wall.facing.getModX() + wall.facing.getModZ() > 0 ? Palette.LAYER_LOOKS_NEGATIVE : 0);
            layers = new LayerSet(palette, new Canvas(palette, layerMaps, 1, sender::wake), row, canvas.cols(), canvas.rows(), speed, side);
        }

        // With a client-placed pointer the camera is the player's own eye, which turns with the mouse:
        // they are held where it is on the flagged spot (see reveal). Otherwise a camera entity looks at
        // the wall and they sit out of the way.
        LivingEntity camera = clientPointer ? null : spawnCamera(cam);
        ArmorStand vehicle = clientPointer ? null : world.spawn(cam.clone().subtract(cam.getDirection().multiply(1.5)).subtract(0, 1.6, 0), ArmorStand.class, a -> {
            a.setMarker(true);
            a.setInvisible(true);
            a.setGravity(false);
            a.setPersistent(false);
            a.setSilent(true);
            a.setInvulnerable(true);
            a.setVisibleByDefault(false);
        });
        // Right-clicks need something to hit: an invisible Interaction just in front of the camera, or, when
        // the camera turns, a tiny one round the eye, which a look in any direction hits first.
        Location ahead = clientPointer ? cam.clone().subtract(0, CLIENT_TARGET / 2, 0)
                : cam.clone().add(cam.getDirection().multiply(1.5)).subtract(0, 0.5, 0);
        float targetSize = clientPointer ? CLIENT_TARGET : 1f;
        Interaction target = world.spawn(ahead, Interaction.class, i -> {
            i.setInteractionWidth(targetSize);
            i.setInteractionHeight(targetSize);
            i.setResponsive(false);
            i.setPersistent(false);
            i.setVisibleByDefault(false);
        });
        Location pointerAt = cam.clone();
        if (clientPointer) {
            // Ahead on the flagged axis, square to it and facing the camera; its distance tells the shader the speed.
            pointerAt.add(cam.getDirection().multiply(Shaders.clientPointerDistance(speed)));
            pointerAt.setYaw(MapWall.yawFacing(wall.facing.getModX(), wall.facing.getModZ()));
            pointerAt.setPitch(0);
        }
        TextDisplay pointer = !withPack ? null : world.spawn(pointerAt, TextDisplay.class, d -> {
            d.setBackgroundColor(org.bukkit.Color.fromARGB(0));
            d.setShadowed(false);
            d.setBillboard(clientPointer ? Display.Billboard.FIXED : Display.Billboard.CENTER);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(clientPointer ? 0 : 1);
            d.setPersistent(false);
            d.setVisibleByDefault(false);
        });
        // An overlay's switch for the post effect that keeps the world round it (Shaders.overlayChain): the game runs it
        // only while a glowing entity is drawn. A display of no size is never culled (Display.updateCulling), and this
        // one, a speck at the eye, draws nothing anybody sees.
        BlockDisplay glow = !keeps ? null : world.spawn(cam.clone(), BlockDisplay.class, d -> {
            d.setBlock(Material.STONE.createBlockData());
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.001f), new Quaternionf()));
            d.setGlowing(true);
            d.setPersistent(false);
            d.setVisibleByDefault(false);
        });
        player.showEntity(plugin, target);
        if (vehicle != null) player.showEntity(plugin, vehicle);
        if (camera != null) player.showEntity(plugin, camera);

        ViewImpl view = new ViewImpl(player, content, canvas, wall, options, withPack, clientPointer, keeps, speed, vehicle, seat, camera, target, pointer, glow, from, cam, layers);
        views.put(player.getUniqueId(), view);
        // Remembered on the player, so a crash mid-view cannot leave them stuck in adventure mode or in the ground.
        player.getPersistentDataContainer().set(seatKey, PersistentDataType.STRING, player.getGameMode().name());
        player.getPersistentDataContainer().set(returnKey, PersistentDataType.STRING, encode(from));
        player.setGameMode(GameMode.ADVENTURE);
        // An empty hotbar until they get up, or scrolling would pop up the held item's name.
        pipeline.conceal(player, true);
        pipeline.watch(player, new Pipeline.Mouse() {
            @Override public void turned(float yaw, float pitch) { Views.this.turned(view, yaw, pitch); }
            @Override public void teleported() { view.awaitingTeleport = false; }
            @Override public void clicked() { Views.this.clicked(view); }
            @Override public void tickEnded() { Views.this.tickEnded(view); }
            @Override public void dropped() { Views.this.dropped(view); }
        }, view.pointerId);
        // Hides the first-person arm; nobody else sees the seated player at all.
        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        // Rain and snow are drawn between the camera and the picture: clear skies for this player only.
        player.setPlayerWeather(WeatherType.CLEAR);
        for (Player other : Bukkit.getOnlinePlayers()) if (other != player) other.hidePlayer(plugin, player);
        return view;
    }

    /**
     * A camera with a player's eye height. The client glides its eye height towards the new camera's by
     * half each tick, so from a player to an armour stand (eyes at its feet) the picture slid into place
     * for most of a second, off the flagged position the whole time. Mannequins came in 1.21.9; before
     * that an armour stand is the nearest thing.
     */
    private LivingEntity spawnCamera(Location eyes) {
        World world = eyes.getWorld();
        if (mannequin != null && mannequin.getEntityClass() != null) {
            Entity e = world.spawn(eyes.clone().subtract(0, EYE_HEIGHT, 0), mannequin.getEntityClass(), m -> {
                LivingEntity l = (LivingEntity) m;
                l.setInvisible(true);
                l.setGravity(false);
                l.setPersistent(false);
                l.setSilent(true);
                l.setInvulnerable(true);
                l.setCollidable(false);
                l.setVisibleByDefault(false);
                l.setRotation(eyes.getYaw(), eyes.getPitch());
                invoke(m, "setImmovable", boolean.class, true);
                invoke(m, "setDescription", Component.class, null);
            });
            return (LivingEntity) e;
        }
        return world.spawn(eyes.clone().subtract(0, 1.7775, 0), ArmorStand.class, a -> {
            a.setInvisible(true);
            a.setGravity(false);
            a.setPersistent(false);
            a.setSilent(true);
            a.setInvulnerable(true);
            a.setVisibleByDefault(false);
            a.setRotation(eyes.getYaw(), eyes.getPitch());
        });
    }

    private static void invoke(Object target, String method, Class<?> type, Object arg) {
        try {
            target.getClass().getMethod(method, type).invoke(target, arg);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Only on newer servers, and cosmetic.
        }
    }

    /**
     * A standing player's eye height, as their client works it out: 1.62F times their scale, in float
     * ({@code Avatar.STANDING_DIMENSIONS}, {@code EntityDimensions.scale}). Their camera is that far above
     * their feet once it has settled ({@code Camera.tick} and {@code alignWithEntity}), so feet put this far
     * below the flagged spot put the camera on it, to a rounding error far inside the shader's tolerance.
     */
    private static double standingEyeHeight(Player player) {
        return 1.62f * scaleOf(player);
    }

    /** The player's scale as their client reads it (a float). */
    private static float scaleOf(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.SCALE);
        return attr == null ? 1f : (float) attr.getValue();
    }

    /**
     * Where a client-placed view puts [player]'s eye, or null if there is nowhere to hold them. The
     * middle of a block, 3/128 off it along [facing]'s axis, so the shader sees the camera as flagged:
     * the block their eye is in, or the nearest one above or below within {@link #CLIENT_MAX_LIFT}
     * where (1) that block is air, so a look in any direction hits the target round the eye before any
     * block, the client draws no in-wall overlay or fluid fog, and every face is at least 0.27 away; (2)
     * their whole standing box is clear of every collision, or their client would crouch them (which
     * moves the eye) or push them out sideways ({@code Player.updatePlayerPose},
     * {@code LocalPlayer.moveTowardsClosestSpace}); and (3) no lava touches it. Water may: a flying
     * player is not pushed by it (see hold).
     */
    private static Location clientEye(Player player, BlockFace facing) {
        Location at = player.getEyeLocation();
        World world = at.getWorld();
        int bx = at.getBlockX(), bz = at.getBlockZ(), from = at.getBlockY();
        double x = bx + 0.5, z = bz + 0.5;
        if (facing.getModX() != 0) x += Shaders.FLAG_PHASE;
        else z += Shaders.FLAG_PHASE;
        float scale = scaleOf(player);
        double eyeHeight = standingEyeHeight(player);
        // As the client makes a player's box (EntityDimensions.makeBoundingBox), less the 1e-7 it allows when testing a pose.
        double half = 0.6f * scale / 2.0f - 1e-7, height = 1.8f * scale;
        List<Integer> rows = new ArrayList<>();
        for (int y = from - 2; y <= from + 2; y++) if (Math.abs(y + 0.5 - at.getY()) <= CLIENT_MAX_LIFT) rows.add(y);
        rows.sort(java.util.Comparator.comparingDouble(y -> Math.abs(y + 0.5 - at.getY())));
        for (int y : rows) {
            double eyeY = y + 0.5, feet = eyeY - eyeHeight;
            if (feet < world.getMinHeight() || feet + height > world.getMaxHeight()) continue;
            if (!world.getBlockAt(bx, y, bz).getType().isAir()) continue;
            BoundingBox box = new BoundingBox(x - half, feet + 1e-7, z - half, x + half, feet + height - 1e-7, z + half);
            if (Platform.collides(world, box) || touchesLava(world, box)) continue;
            return new Location(world, x, eyeY, z);
        }
        return null;
    }

    /**
     * Where an overlay puts [player]'s eye: their own, standing, with each coordinate moved to a block fraction of
     * k/16 + 3/128 (at most 1/32 of a block), the flag the shaders know an overlay's camera by. Of the eight such
     * spots round it, the nearest where their standing box is clear of every collision and of lava, as for
     * {@link #clientEye}: they are held there. Null if none is (squeezed into a gap).
     */
    static Location overlayEye(Player player) {
        Location feet = player.getLocation();
        World world = feet.getWorld();
        double eyeHeight = standingEyeHeight(player);
        float scale = scaleOf(player);
        double half = 0.6f * scale / 2.0f - 1e-7, height = 1.8f * scale;
        double[] xs = flagged(feet.getX()), ys = flagged(feet.getY() + eyeHeight), zs = flagged(feet.getZ());
        Location best = null;
        double bestDistance = Double.MAX_VALUE;
        for (double x : xs) for (double y : ys) for (double z : zs) {
            double d = sq(x - feet.getX()) + sq(y - eyeHeight - feet.getY()) + sq(z - feet.getZ());
            if (d >= bestDistance) continue;
            double bottom = y - eyeHeight;
            if (bottom < world.getMinHeight() || bottom + height > world.getMaxHeight()) continue;
            BoundingBox box = new BoundingBox(x - half, bottom + 1e-7, z - half, x + half, bottom + height - 1e-7, z + half);
            if (Platform.collides(world, box) || touchesLava(world, box)) continue;
            best = new Location(world, x, y, z);
            bestDistance = d;
        }
        return best;
    }

    /** The two flagged values (k/16 + 3/128) either side of [v], the nearer first. */
    static double[] flagged(double v) {
        double below = Math.floor((v - Shaders.FLAG_PHASE) / Shaders.FLAG_STEP) * Shaders.FLAG_STEP + Shaders.FLAG_PHASE;
        double above = below + Shaders.FLAG_STEP;
        return v - below <= above - v ? new double[]{below, above} : new double[]{above, below};
    }

    private static double sq(double v) {
        return v * v;
    }

    /** The horizontal direction [player] faces most nearly. */
    private static BlockFace cardinal(Player player) {
        BlockFace toward = player.getFacing();
        return MapWall.HORIZONTAL.contains(toward) ? toward : BlockFace.NORTH;
    }

    private static boolean touchesLava(World world, BoundingBox box) {
        for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX()); x++) {
            for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY()); y++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ()); z++) {
                    // Lava is never waterlogged into another block, so the block is the fluid.
                    if (world.getBlockAt(x, y, z).getType() == org.bukkit.Material.LAVA) return true;
                }
            }
        }
        return false;
    }

    /**
     * The block a client-placed view's frames all lie in, on its floor: the camera's own, so each frame's
     * culling box (half a block round it) holds the camera, which is half a block up in it.
     */
    private static int[] stackBlock(Location cam) {
        return new int[]{cam.getBlockX(), cam.getBlockY(), cam.getBlockZ()};
    }

    /**
     * Canvas pixels per degree of turn for [player]'s view of [canvas]: views.pointer-speed, times their own
     * (/mcgui pointer), per mouse count, over the degrees a count turns them by (their {@link MouseSteps}, or
     * the game's default sensitivity's until those are known). A desktop pointer at Windows' default speed
     * moves one screen pixel a count, so 1920 counts across a 1920-pixel-wide screen; the view fills the
     * window, so a speed of 1 crosses it in as many.
     */
    private double speedFor(Player player, Canvas canvas, boolean clientPointer) {
        double perCount = settings.pointerSpeed * pointerSpeedOf(player) * canvas.width() / DESKTOP_WIDTH;
        double step = stepOf(player);
        double speed = perCount / (Double.isNaN(step) ? MouseSteps.DEFAULT : step);
        return clientPointer ? clientSpeed(canvas, speed) : speed;
    }

    /**
     * [speed] for a client-placed pointer: fast enough that the picture's edges are inside
     * {@link #CLIENT_MAX_YAW} and {@link #CLIENT_MAX_PITCH} of its centre (the pointer is where the camera
     * looks, so an edge further round than that could never be reached), and no faster than the shader can
     * be told (the pointer's distance from the eye carries it, see {@link Shaders#clientPointerDistance}).
     */
    private static double clientSpeed(Canvas canvas, double speed) {
        speed = Math.max(speed, canvas.width() / 2.0 / CLIENT_MAX_YAW);
        speed = Math.max(speed, canvas.height() / 2.0 / CLIENT_MAX_PITCH);
        return Math.min(speed, Shaders.CLIENT_POINTER_MAX_SPEED);
    }

    /** The degrees one count of [player]'s mouse turns them by, or NaN while not known (see {@link MouseSteps}). */
    private double stepOf(Player player) {
        MouseSteps m = mice.get(player.getUniqueId());
        if (m != null) return m.step();
        Double known = player.getPersistentDataContainer().get(stepKey, PersistentDataType.DOUBLE);
        return known == null ? Double.NaN : known;
    }

    /** [player]'s mouse sensitivity as their options hold it (0 to 1; the game shows twice that as a percentage), or NaN while not known. */
    public double sensitivityOf(Player player) {
        double step = stepOf(player);
        return Double.isNaN(step) ? Double.NaN : MouseSteps.sensitivity(step);
    }

    /** [player]'s own pointer speed, a multiple of views.pointer-speed set with /mcgui pointer. */
    public double pointerSpeedOf(Player player) {
        Double speed = player.getPersistentDataContainer().get(pointerKey, PersistentDataType.DOUBLE);
        return speed == null ? 1.0 : speed;
    }

    /** Takes effect from the next view [player] opens. */
    public void setPointerSpeed(Player player, double speed) {
        if (speed == 1.0) player.getPersistentDataContainer().remove(pointerKey);
        else player.getPersistentDataContainer().set(pointerKey, PersistentDataType.DOUBLE, speed);
    }

    /** For /mcgui debug: the pointer's speed in [player]'s open view, and what it came from. */
    public String pointerSpeedReport(Player player) {
        ViewImpl v = views.get(player.getUniqueId());
        double sensitivity = sensitivityOf(player);
        return String.format(Locale.ROOT, "%s px/degree, sensitivity %s, step %s degrees per count, own speed %.2f",
                v == null ? "no view" : String.format(Locale.ROOT, "%.3f", v.speed),
                Double.isNaN(sensitivity) ? "not known yet" : String.format(Locale.ROOT, "%.4f (%d%%)", sensitivity, Math.round(sensitivity * 200)),
                String.format(Locale.ROOT, "%.6f", stepOf(player)), pointerSpeedOf(player));
    }

    /**
     * Every tick: each online player's pitch, from which their {@link MouseSteps} learns how far a count of
     * their mouse turns them. Remembered on the player once known, so their first view after a restart is
     * already right. Not across a turn the server made: a teleport or respawn ({@link #holdMouse}),
     * or one a view makes ({@link #turnedByServer}).
     */
    private void learnMice() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            MouseSteps m = mice.get(p.getUniqueId());
            if (m == null) {
                Double known = p.getPersistentDataContainer().get(stepKey, PersistentDataType.DOUBLE);
                m = new MouseSteps(known == null ? Double.NaN : known);
                mice.put(p.getUniqueId(), m);
            }
            ViewImpl v = views.get(p.getUniqueId());
            if (v != null && turnedByServer(v)) m.hold();
            if (m.sample(p.getLocation().getPitch())) {
                p.getPersistentDataContainer().set(stepKey, PersistentDataType.DOUBLE, m.step());
                debug(String.format(Locale.ROOT, "%s's mouse turns %.6f degrees a count: sensitivity %.4f", p.getName(), m.step(), MouseSteps.sensitivity(m.step())));
            }
        }
    }

    /**
     * Server thread: whether [v] is turning its player, or has since the last look: waiting for the seat
     * teleport to be taken, turning them back inside the picture, levelling their head, or a debug point.
     * Their pitch across any of those is not whole counts of their mouse.
     */
    private static boolean turnedByServer(ViewImpl v) {
        int turns = v.serverTurns.get();
        boolean turned = turns != v.serverTurnsSeen;
        v.serverTurnsSeen = turns;
        return turned || v.awaitingTeleport || v.correcting || v.pitchReset;
    }

    /**
     * A wall in front of [player] at eye level, far enough away to fill their view from about their
     * own block column. Its frames can be inside the ground: they are drawn in front of it.
     */
    private MapWall inFront(Player player, int cols, int rows, double fov) {
        BlockFace toward = cardinal(player);
        BlockFace facing = toward.getOppositeFace();
        World world = player.getWorld();
        int eyeY = (int) Math.floor(player.getEyeLocation().getY());
        int top = Math.min(world.getMaxHeight() - 1, Math.max(world.getMinHeight() + rows - 1, eyeY + rows / 2));
        int ahead = (int) Math.ceil(MapWall.viewDistance(cols, rows, fov)) + 1;
        int cx = player.getLocation().getBlockX() + toward.getModX() * ahead;
        int cz = player.getLocation().getBlockZ() + toward.getModZ() * ahead;
        int half = (cols - 1) / 2;
        MapWall wall = new MapWall(world, cx - facing.getModZ() * half, top, cz + facing.getModX() * half, facing, cols, rows, firstMapId);
        return wall.placeable() ? wall : null;
    }

    /** Every tick: learn mice, switch waiting players to their view, and turn mouse movement into pointer movement. */
    public void tick() {
        learnMice();
        int now = Platform.tick();
        for (ViewImpl v : views.values()) {
            Player p = Bukkit.getPlayer(v.player);
            if (p == null) continue;
            if (!v.revealed) {
                if (v.stage == 1) {
                    // Taking the world: kept once the client has had a few frames with the signature since it answered
                    // the ping sent with it, or at the latest when keepBy is up.
                    int since = v.takenSince;
                    if ((since < 0 || now < since + KEEP_AFTER) && now < v.keepBy) continue;
                    keep(p, v);
                } else {
                    if (now < v.deadline) {
                        // Written to the network is not yet shown: wait for the client to have handled the tiles too.
                        if (!v.pinged && sender.delivered(v.player, v.wall)) {
                            v.pinged = true;
                            pipeline.afterClientCaughtUp(p, () -> v.caughtUp = true);
                        }
                        if (!v.caughtUp) continue;
                    }
                    if (v.keeps) {
                        take(p, v);
                        continue;
                    }
                    reveal(p, v);
                }
            }
            if (v.keeps) {
                signature(p, v);
                // The panel grew (something opened past it): its new tiles' frames, once their maps have reached the client.
                if (!v.wall.allShown() && sender.delivered(v.player, v.wall)) v.wall.showTo(p);
            }
            if (v.layers != null && v.revealed) frameLayers(p, v);
            // A client that never takes the teleport (another plugin cancelled it), or a kept overlay's turn, must not
            // leave the mouse dead.
            if (v.awaitingTeleport && now >= v.settleBy) v.awaitingTeleport = false;
            if (v.aligning && now >= v.keepBy) v.aligning = false;
            if (v.clientPointer) {
                keepStill(p, v);
                settleClicks(v, now);
            }
            report(v);
            movePointer(v);
            if (now % 40 == 0) v.privateView.refresh(p);
        }
    }

    /**
     * Gives the client a frame for each hover-layer map a layer holds, and takes the rest away (LayerSet), once the maps
     * as they are now have been written to it: a new frame then shows its layer's map, and one taken away was already
     * marked to show nothing, in the same pass as the picture that made its layer stale.
     */
    private void frameLayers(Player p, ViewImpl v) {
        int changes = v.layers.changes();
        if (changes == v.layersFramed) return;
        // Read before the check, so a layer armed after it waits for the next tick rather than going out unsent.
        boolean[] held = v.layers.framed();
        if (!sender.delivered(v.player, v.layers.wall)) return;
        v.layers.wall.frameOnly(p, held);
        v.layersFramed = changes;
    }

    /**
     * A kept overlay, first step: the player is held where they are, their eye on the flag (at most 1/32 of a block
     * off, their rotation untouched), and their client is sent the signature (map and frame) and the glowing switch,
     * but not yet the panel. From the next frame it draws, the post effect takes the world as it is every frame
     * (Shaders.overlayChain), which is still exactly what the player was looking at.
     */
    private void take(Player p, ViewImpl v) {
        v.stage = 1;
        v.keepBy = Platform.tick() + KEEP_TIMEOUT;
        v.settleBy = Platform.tick() + SETTLE_TIMEOUT;
        v.awaitingTeleport = true;
        hold(p);
        p.setGliding(false);
        nms.teleportKeepingRotation(p, v.seat.getX(), v.seat.getY(), v.seat.getZ());
        // The map before its frame, or the frame shows the map item.
        signature(p, v);
        v.wall.showSignature(p);
        p.showEntity(plugin, v.glow);
        pipeline.afterClientCaughtUp(p, () -> v.takenSince = Platform.tick());
    }

    /**
     * A kept overlay, second step: the signature says the world is kept, and the player is turned to face the
     * picture's axis, level, which the client pointer starts from (the camera turning is not seen: the world round
     * the panel is the kept one), in one bundle, so the first frame after it shows both or neither. Then the panel
     * and the pointer, which shows once the turn has been taken.
     */
    private void keep(Player p, ViewImpl v) {
        v.stage = 2;
        v.keepBy = Platform.tick() + SETTLE_TIMEOUT;
        v.aligning = true;
        v.serverTurns.incrementAndGet();
        v.revealed = true;
        int flags = signatureFlags(v);
        v.signatureFlags = flags;
        nms.send(p, nms.bundle(List.of(
                nms.mapPatch(v.wall.signatureMapId(), 6, 0, 1, 1, new byte[]{palette.signatureFlags(flags)}),
                nms.rotate(v.refYaw, false, 0f, false))));
        v.wall.showTo(p);
        v.privateView.open(plugin, p);
        movePointer(v);
        p.showEntity(plugin, v.pointer);
        pipeline.afterClientCaughtUp(p, () -> {
            v.aligning = false;
            v.serverTurns.incrementAndGet();
        });
    }

    /** A kept overlay's signature flags: blur if asked, and kept once it has been taken. */
    private static int signatureFlags(ViewImpl v) {
        return (v.options.blur() ? Shaders.SIGNATURE_BLUR : 0) | (v.stage >= 2 ? Shaders.SIGNATURE_KEPT : 0);
    }

    /** Sends a kept overlay's signature map whenever its tint or flags change (the tint follows the canvas's). */
    private void signature(Player p, ViewImpl v) {
        int tint = v.shown.tint(), flags = signatureFlags(v);
        if (tint == v.signatureTint && flags == v.signatureFlags) return;
        v.signatureTint = tint;
        v.signatureFlags = flags;
        nms.send(p, nms.mapPatch(v.wall.signatureMapId(), 0, 0, 128, 128, palette.markSignature(v.wall.cols, v.wall.rows, tint, flags)));
    }

    private void reveal(Player p, ViewImpl v) {
        // Before revealed, so turned() never reads a rotation from before the teleport below as the start.
        v.settleBy = Platform.tick() + SETTLE_TIMEOUT;
        v.awaitingTeleport = true;
        v.revealed = true;
        // All in one tick, so the client applies them together with the camera switch.
        v.wall.showTo(p);
        v.privateView.open(plugin, p);
        if (v.pointer != null) {
            // Spawned for them where it belongs: the server's later moves of it never reach them (see Pipeline.watch).
            movePointer(v);
            p.showEntity(plugin, v.pointer);
            if (!v.clientPointer) sendPointer(v);
        }
        if (v.clientPointer) {
            // Held first, so their client never runs a tick at the seat in which it could move them. Their
            // eye is on the flagged spot from the first frame after the teleport, looking at the picture's
            // centre, and their own eye stays the camera.
            hold(p);
            p.setGliding(false);
            p.teleport(v.seat);
        } else {
            p.teleport(v.vehicle.getLocation());
            v.vehicle.addPassenger(p);
            nms.setCamera(p, v.camera);
            // Again once everything has settled, in case the teleport and mount reset it. The empty action
            // bar replaces the game's own "Press Shift to Dismount", which the client shows on mounting.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (views.get(v.player) != v || !p.isOnline()) return;
                nms.setCamera(p, v.camera);
                Platform.actionBar(p, Component.empty());
            }, 5);
        }
    }

    /** What {@link #hold} changes, each under the one key {@code uiengine:seated}. */
    private static final List<Attribute> HELD = List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH, Attribute.GRAVITY,
            Attribute.STEP_HEIGHT, Attribute.KNOCKBACK_RESISTANCE, Attribute.EXPLOSION_KNOCKBACK_RESISTANCE);

    /**
     * Holds [player] still for a client-placed pointer, where nothing they press moves their eye. Their
     * client is told they fly, at a flying and walking speed of nothing, and cannot stop (see
     * {@link Pipeline#hold}): with their feet in the air a player otherwise moves 0.02 a tick for each
     * movement key whatever their movement_speed ({@code Player.getFlyingSpeed}), and flying also turns off
     * crouching (which lowers the eye), swimming, climbing, gliding, water currents, bubble columns,
     * levitation and cobwebs ({@code Player}, {@code LivingEntity.travelInAir}). Then attribute modifiers,
     * transient so a crash cannot save them (on Paper; Spigot saves them, and {@link #onJoin} takes
     * them off), all under one key so {@link #release} can always take them off: movement_speed, jump_strength, gravity and step_height times zero (a total multiplier of -1
     * zeroes whatever the other modifiers add up to), knockback_resistance and
     * explosion_knockback_resistance plus one. Gravity at zero also keeps the server from kicking them
     * for floating ({@code ServerGamePacketListenerImpl.getMaximumFlyingTicks}).
     */
    private void hold(Player player) {
        for (Attribute a : HELD) {
            AttributeInstance attr = player.getAttribute(a);
            if (attr == null) continue;
            boolean resistance = a == Attribute.KNOCKBACK_RESISTANCE || a == Attribute.EXPLOSION_KNOCKBACK_RESISTANCE;
            Platform.removeModifier(attr, seatKey);
            Platform.addTransient(attr, new AttributeModifier(seatKey, resistance ? 1 : -1,
                    resistance ? AttributeModifier.Operation.ADD_NUMBER : AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                    org.bukkit.inventory.EquipmentSlotGroup.ANY));
        }
        pipeline.hold(player.getUniqueId(), true);
        nms.resendAbilities(player);
    }

    /** Undoes {@link #hold}, whether or not it was done: safe on every way out, and on join after a crash. */
    private void release(Player player) {
        removeModifiers(player);
        pipeline.hold(player.getUniqueId(), false);
        if (player.isOnline()) nms.resendAbilities(player);
    }

    /** Takes {@link #hold}'s modifiers off [player], by key: nothing happens to an attribute without one. */
    private void removeModifiers(Player player) {
        for (Attribute a : HELD) {
            AttributeInstance attr = player.getAttribute(a);
            if (attr != null) Platform.removeModifier(attr, seatKey);
        }
    }

    /**
     * A held player's client still moves them when something pushes them: an entity overlapping them
     * (their client runs {@code LivingEntity.pushEntities} for itself) or a piston. So everything that could
     * push them is hidden from them as it comes near, and if their client reports them anywhere but the
     * seat all the same, they are put back, with their rotation left alone.
     */
    private void keepStill(Player p, ViewImpl v) {
        for (Entity e : p.getWorld().getNearbyEntities(p.getBoundingBox().expand(PUSH_MARGIN))) {
            if (e instanceof LivingEntity || e instanceof Vehicle) v.privateView.hide(p, e);
        }
        if (v.awaitingTeleport) return;
        Location at = p.getLocation();
        if (at.getX() != v.seat.getX() || at.getY() != v.seat.getY() || at.getZ() != v.seat.getZ()) {
            debug("moved off the seat by " + at.toVector().subtract(v.seat.toVector()) + ": putting " + p.getName() + " back");
            nms.teleportKeepingRotation(p, v.seat.getX(), v.seat.getY(), v.seat.getZ());
        }
    }

    /**
     * The player turned their head to (yaw, pitch), on the netty thread as the packet arrives: move the
     * cursor by how far they turned, and the pointer with it.
     */
    private void turned(ViewImpl v, float yaw, float pitch) {
        synchronized (v) {
            if (!v.revealed || v.closed) return;
            // Until the client has taken the teleport to the seat (or a kept overlay's turn to its axis), its rotation
            // is from before it, and starting from that would throw the pointer by the difference the moment it appears.
            if (v.awaitingTeleport || v.aligning) return;
            if (v.clientPointer) {
                turnedClient(v, yaw, pitch);
                return;
            }
            if (!v.primed) {
                v.lastYaw = yaw;
                v.lastPitch = pitch;
                v.primed = true;
                return;
            }
            float dyaw = wrap(yaw - v.lastYaw);
            float dpitch = pitch - v.lastPitch;
            // After a level head was sent, the first rotation from after the client took it starts from 0.
            // Turns are small next to the jump back to level, so whichever start is nearer is the right one.
            if (v.pitchReset && Math.abs(pitch) < Math.abs(dpitch)) {
                dpitch = pitch;
                v.pitchReset = false;
                v.serverTurns.incrementAndGet();
            }
            v.lastYaw = yaw;
            v.lastPitch = pitch;
            if (dyaw != 0 || dpitch != 0) {
                // In pixels per degree, scaled with the picture so the pointer crosses it at the same pace at any size.
                v.cursorX = Math.max(0, Math.min(v.canvas.width() - 1.0, v.cursorX + dyaw * v.speed));
                v.cursorY = Math.max(0, Math.min(v.canvas.height() - 1.0, v.cursorY + dpitch * v.speed));
                sendPointer(v);
                queueReport(v);
            }
            if (!v.pitchReset && Math.abs(pitch) > PITCH_LIMIT) {
                v.pitchReset = true;
                v.serverTurns.incrementAndGet();
                pipeline.sendNow(v.player, nms.pitch(0f));
            }
        }
    }

    /**
     * A client-placed pointer: where the player looks IS the cursor, worked out exactly as their shader
     * does (the picture's centre plus the speed times the turn from straight at it, held inside the
     * picture), from the same rotation, so the two agree for every rotation the client sends. Past an
     * edge the pointer stays on it, and the player is turned back to the edge, so that turning back moves
     * it at once; one such turn at a time, until the client has answered the ping sent after it (the
     * rotations before then may still be from before it). Under the view's lock.
     */
    private void turnedClient(ViewImpl v, float yaw, float pitch) {
        double w = v.canvas.width(), h = v.canvas.height();
        // The shader sees the turn only within ±180°; the turn back past an edge goes by the whole turn
        // (the same angle, give or take whole turns, nearest to where the last rotation left it), so a
        // flick right round the back is still undone the way it went.
        double turn = wrap(yaw - v.refYaw);
        v.turnYaw = v.primed ? turn + 360 * Math.round((v.turnYaw + wrap(yaw - v.lastYaw) - turn) / 360) : turn;
        v.lastYaw = yaw;
        v.primed = true;
        double x = w / 2 + v.speed * turn, y = h / 2 + v.speed * pitch;
        double cx = Math.max(0, Math.min(w - 1.0, x)), cy = Math.max(0, Math.min(h - 1.0, y));
        double whole = w / 2 + v.speed * v.turnYaw;
        boolean moved = cx != v.cursorX || cy != v.cursorY;
        v.cursorX = cx;
        v.cursorY = cy;
        // The rotation after a click: where its pointer was drawn.
        if (v.clicksPlaced != v.clicksSeen) placeClicks(v, "its rotation");
        if (moved) queueReport(v);
        double backX = Math.max(0, Math.min(w - 1.0, whole)) - whole, backY = cy - y;
        if (!v.correcting && (Math.abs(backX) > 0.5 || Math.abs(backY) > 0.5)) {
            Player p = Bukkit.getPlayer(v.player);
            if (p == null) return;
            v.correcting = true;
            v.serverTurns.incrementAndGet();
            float dyaw = (float) (backX / v.speed), dpitch = (float) (backY / v.speed);
            debug("past the edge: turning back " + dyaw + "," + dpitch);
            pipeline.sendNow(v.player, nms.rotate(dyaw, true, dpitch, true));
            pipeline.afterClientCaughtUp(p, () -> {
                v.correcting = false;
                v.serverTurns.incrementAndGet();
            });
        }
    }

    /** Tell the content now, on the server thread as soon as it is free, rather than at the next tick. */
    private void queueReport(ViewImpl v) {
        if (v.reportQueued.compareAndSet(false, true)) {
            nms.serverThread().execute(() -> {
                v.reportQueued.set(false);
                if (!v.closed && views.get(v.player) == v) report(v);
            });
        }
    }

    /** On the netty thread: a click or wheel step went by, before its tick's rotation. */
    private void clicked(ViewImpl v) {
        synchronized (v) {
            v.clicksSeen++;
            v.preClickX = v.cursorX;
            v.preClickY = v.cursorY;
        }
    }

    /**
     * On the netty thread: one of the client's ticks ended. A client that is not riding sends its rotation
     * only when it has changed ({@code LocalPlayer.sendPosition}), and sends it after that tick's clicks and
     * before this ({@code Minecraft.tick}): a click no rotation has followed by now was made with the mouse
     * still, where the pointer already is.
     */
    private void tickEnded(ViewImpl v) {
        synchronized (v) {
            if (!v.clientPointer || !v.revealed || v.closed || v.awaitingTeleport) return;
            if (v.clicksPlaced != v.clicksSeen) placeClicks(v, "the end of its tick (a still mouse)");
        }
    }

    /**
     * Server thread, each tick: clicks still waiting from an earlier tick go where the pointer is now (a
     * client that never says its tick has ended, or a rotation lost on the way). Never before their next tick.
     */
    private void settleClicks(ViewImpl v, int now) {
        synchronized (v) {
            if (!v.waitingClicks.isEmpty() && now > v.waitingSince) placeClicks(v, "the next server tick");
        }
    }

    /**
     * Every click seen so far was made with the pointer at the cursor as it is now: remember that for
     * the clicks still to be handled, and run the ones already waiting. Under the view's lock.
     */
    private void placeClicks(ViewImpl v, String after) {
        v.clicksPlaced = v.clicksSeen;
        v.clickX = v.cursorX;
        v.clickY = v.cursorY;
        if (v.waitingClicks.isEmpty()) return;
        List<java.util.function.BiConsumer<Integer, Integer>> run = new ArrayList<>(v.waitingClicks);
        v.waitingClicks.clear();
        int px = (int) v.clickX, py = (int) v.clickY;
        debug(run.size() + " click(s) placed after " + after + " at " + px + "," + py);
        nms.serverThread().execute(() -> {
            if (v.closed || views.get(v.player) != v) return;
            for (java.util.function.BiConsumer<Integer, Integer> click : run) click.accept(px, py);
        });
    }

    /**
     * Runs [click] with where the pointer was when it was made. For a client-placed pointer that is the
     * cursor from the rotation after the click on the wire, which the client sent the same tick (see
     * {@link ViewImpl#clicksSeen}), or, with the mouse still, the cursor as it was; if neither is known
     * yet, [click] waits (see tickEnded and settleClicks). Server thread.
     */
    private void atClick(ViewImpl v, java.util.function.BiConsumer<Integer, Integer> click) {
        int x, y;
        synchronized (v) {
            if (!v.clientPointer) {
                x = (int) v.cursorX;
                y = (int) v.cursorY;
            } else if (v.clicksPlaced == v.clicksSeen) {
                x = (int) v.clickX;
                y = (int) v.clickY;
                // Where the cursor was before the click's own rotation: what using it would have missed by.
                debug("click at " + x + "," + y + ", before its rotation " + (int) v.preClickX + "," + (int) v.preClickY);
            } else {
                debug("click waits for its rotation, before it " + (int) v.preClickX + "," + (int) v.preClickY);
                if (v.waitingClicks.isEmpty()) v.waitingSince = Platform.tick();
                v.waitingClicks.add(click);
                return;
            }
        }
        click.accept(x, y);
    }

    /** A {@code [pointer]} line in the log, with views.debug-pointer on. */
    private void debug(String message) {
        if (settings.debugPointer) plugin.getLogger().info("[pointer] " + message);
    }

    /** Sends the pointer to where the cursor is, now; the client glides it there over a tick. */
    private void sendPointer(ViewImpl v) {
        if (v.pointerId < 0) return;
        Vector at = pointerSpot(v);
        pipeline.sendNow(v.player, nms.teleport(v.pointerId, at.getX(), at.getY(), at.getZ()));
    }

    /** On the picture, at the cursor: the shader stretches it to the window along with the picture. */
    private static Vector pointerSpot(ViewImpl v) {
        return v.wall.pointAt(v.cursorX, v.cursorY);
    }

    /**
     * Tell the content where the pointer is, on the server thread, whenever it is over a new pixel. Runs
     * as soon as a turn moves the cursor (see turned) and again each tick, which then finds nothing new.
     */
    private void report(ViewImpl v) {
        int x = (int) v.cursorX, y = (int) v.cursorY;
        // A view opens with the pointer in the middle, wherever the player's mouse is: whatever happens to be
        // there should not light up until they move it, so the first report waits for a deliberate move.
        if (!v.pointerMoved) {
            double off = Math.hypot(v.cursorX - v.canvas.width() / 2.0, v.cursorY - v.canvas.height() / 2.0);
            if (off < Math.max(4, v.canvas.width() / 64.0)) return;
            v.pointerMoved = true;
        }
        if (x != v.lastPointerX || y != v.lastPointerY) {
            v.lastPointerX = x;
            v.lastPointerY = y;
            try {
                v.content.pointer(v, x, y);
            } catch (RuntimeException | LinkageError e) {
                failed("on a pointer move", e);
            }
        }
    }

    private void movePointer(ViewImpl v) {
        TextDisplay pointer = v.pointer;
        if (pointer == null) return;
        // A kept overlay's pointer shows once the turn to its axis has been taken: before that it is drawn from wherever
        // the player was looking.
        Pointer shape = v.aligning ? Pointer.HIDDEN : v.shape;
        if (shape != v.shownShape) {
            v.shownShape = shape;
            int size = v.options.pointerSize() > 0 ? v.options.pointerSize() : Math.max(16, v.canvas.height() / 40);
            float s = (float) (size / 128.0 / (16 * GLYPH_PX));
            // Put the shape's hot pixel on the origin: the line is centred on the glyph's advance, and its top
            // is 10 glyph pixels above the origin.
            pointer.setTransformation(new Transformation(
                    new Vector3f((shape.advance() / 2f - shape.hotX) * GLYPH_PX * s, (TOP_Y + shape.hotY) * GLYPH_PX * s, 0f),
                    new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
            int rgb = v.clientPointer ? Shaders.clientPointerRgb(v.wall.cols, v.wall.rows) : Shaders.pointerRgb(v.wall.cols, v.wall.rows);
            Platform.text(pointer, shape == Pointer.HIDDEN ? Component.empty()
                    : Component.text(String.valueOf(shape.glyph)).font(ResourcePack.POINTER_FONT).color(TextColor.color(rgb)));
        }
        // A client-placed pointer never moves: the shader places it from where the camera looks.
        if (v.clientPointer) return;
        // The server's copy follows too, for anyone it is (re)spawned for; its own position updates never
        // reach the player (see Pipeline.watch), the moves sent from turned() do.
        Vector at = pointerSpot(v);
        Location current = pointer.getLocation();
        if (current.getX() != at.getX() || current.getY() != at.getY() || current.getZ() != at.getZ()) {
            pointer.teleport(new Location(pointer.getWorld(), at.getX(), at.getY(), at.getZ(), current.getYaw(), current.getPitch()));
        }
    }

    /** What every open view needs sent: its wall, its canvas, and for plain viewers the pointer. */
    public void assignments(Collection<MapSender.Assignment> out) {
        for (ViewImpl v : views.values()) {
            Player p = Bukkit.getPlayer(v.player);
            if (p == null) continue;
            Canvas canvas;
            try {
                canvas = v.content.canvas();
            } catch (RuntimeException | LinkageError e) {
                // One broken view must not stop the list, and with it every screen, from being sent.
                failed("giving its canvas", e);
                canvas = v.canvas;
            }
            // Nothing to show yet (the content is still drawing it): nothing is sent, and the switch waits for it.
            if (canvas == null) continue;
            if (canvas.cols() != v.wall.cols || canvas.rows() != v.wall.rows) canvas = v.canvas;
            v.shown = canvas;
            if (v.keeps) panel(v, canvas.snapshot());
            Canvas.Encoding hi = v.clientPointer ? Canvas.Encoding.HI_TILED : Canvas.Encoding.HI_OWN;
            if (v.withPack) {
                out.add(new MapSender.Assignment(p, v.wall, canvas, hi, null));
                // After the view's own, so a layer taken off as the picture under it changes goes out in the same pass.
                if (v.layers != null) out.add(new MapSender.Assignment(p, v.layers.wall, v.layers.canvas, Canvas.Encoding.HI, null));
            } else {
                out.add(new MapSender.Assignment(p, v.wall, canvas, Canvas.Encoding.PLAIN,
                        v.revealed ? new MapSender.PointerSpot((int) v.cursorX, (int) v.cursorY, v.shape) : null));
            }
        }
    }

    /**
     * A kept overlay sends and frames only the tiles with something opaque in them, its panel, and nothing is drawn
     * where the rest would be: the world shows there because nothing covers it. Only ever more of them: a tile the
     * panel leaves keeps its frame, and shows nothing.
     */
    private void panel(ViewImpl v, Canvas.Snapshot snap) {
        if (snap.sameAs(v.panelOf)) return;
        v.panelOf = snap;
        boolean[] tiles = new boolean[v.wall.tiles()];
        for (int t = 0; t < tiles.length; t++) tiles[t] = snap.opaque(t);
        int before = v.wall.sendsVersion();
        v.wall.sendOnly(tiles);
        if (v.wall.sendsVersion() != before) sender.wake();
    }

    public void close(Player player, CloseReason reason) {
        ViewImpl v = views.remove(player.getUniqueId());
        if (v == null) return;
        v.closed = true;
        pipeline.unwatch(player.getUniqueId());
        // The player first, and the content after: a content that throws must never leave them seated,
        // invisible and hidden, with the view already gone so that nothing could close it again.
        if (player.isOnline() && v.vehicle != null) {
            nms.setCamera(player, player);
            v.vehicle.removePassenger(player);
        }
        // Back where they were, facing where they faced, before the frames and the overlay's signature go: a kept
        // overlay's client shows the world as it was until then, so the first live frame is from the same spot.
        // Not when they have already gone to another world (the move closed the view): that is where they were sent.
        if (player.isOnline() && player.getWorld() == v.wall.world) player.teleport(v.returnTo);
        for (Entity e : v.entities()) e.remove();
        PotionEffect own = v.previousInvisibility;
        if (own != null && !own.isInfinite()) {
            // Sitting does not stop its clock.
            int left = own.getDuration() - (Platform.tick() - v.openedAt);
            own = left > 0 ? new PotionEffect(own.getType(), left, own.getAmplifier(), own.isAmbient(), own.hasParticles(), own.hasIcon()) : null;
        }
        restore(player, v.previousMode, own);
        if (v.heldSlot >= 0 && player.isOnline()) player.getInventory().setHeldItemSlot(v.heldSlot);
        if (v.previousWeather != null) player.setPlayerWeather(v.previousWeather);
        else player.resetPlayerWeather();
        // Players still seated stay hidden from everyone (see open), whatever this view hid of them.
        v.privateView.close(plugin, player, e -> e instanceof Player other && views.containsKey(other.getUniqueId()));
        v.wall.despawn();
        if (v.layers != null) v.layers.wall.despawn();
        player.getPersistentDataContainer().remove(returnKey);
        for (Map.Entry<Key, Boolean> held : v.held.entrySet()) {
            if (!held.getValue()) continue;
            try {
                v.content.key(v, held.getKey(), false);
            } catch (RuntimeException | LinkageError e) {
                failed("on a key release", e);
            }
        }
        try {
            v.content.closed(v, reason);
        } catch (RuntimeException | LinkageError e) {
            failed("on close", e);
        }
    }

    /**
     * A content callback threw: say so, and carry on with everything else. Callers catch LinkageError
     * too: a content whose plugin was disabled before this one can no longer load its classes.
     */
    private void failed(String when, Throwable e) {
        plugin.getLogger().log(Level.WARNING, "A view's content failed " + when, e);
    }

    /** [invisibility] is the player's own, put back after the view's; null for none. */
    private void restore(Player player, GameMode mode, PotionEffect invisibility) {
        player.setGameMode(mode);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        if (invisibility != null) player.addPotionEffect(invisibility);
        player.getPersistentDataContainer().remove(seatKey);
        for (Player other : Bukkit.getOnlinePlayers()) other.showPlayer(plugin, player);
        pipeline.conceal(player, false);
        release(player);
    }

    public void closeAll(CloseReason reason) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            // Each on its own: one that fails must not leave everyone after them seated.
            try {
                close(p, reason);
            } catch (RuntimeException | LinkageError e) {
                plugin.getLogger().log(Level.SEVERE, "Could not close " + p.getName() + "'s view", e);
            }
        }
    }

    private static String encode(Location l) {
        return String.join(";", l.getWorld().getName(), String.valueOf(l.getX()), String.valueOf(l.getY()), String.valueOf(l.getZ()),
                String.valueOf(l.getYaw()), String.valueOf(l.getPitch()));
    }

    private static Location decode(String s) {
        try {
            String[] p = s.split(";");
            World w = Bukkit.getWorld(p[0]);
            if (w == null) return null;
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static float wrap(float deg) {
        float d = deg % 360;
        if (d >= 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    // ---------------------------------------------------------------- input

    private ViewImpl of(Player p) {
        ViewImpl v = views.get(p.getUniqueId());
        return v != null && v.revealed ? v : null;
    }

    /** On join: undo a view the server crashed out of, and hide everyone currently seated. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player player = e.getPlayer();
        // Transient on Paper, so never saved there; Spigot saves them with the player, and this takes them off.
        removeModifiers(player);
        String mode = player.getPersistentDataContainer().get(seatKey, PersistentDataType.STRING);
        if (mode != null) {
            GameMode gm;
            try {
                gm = GameMode.valueOf(mode);
            } catch (IllegalArgumentException ex) {
                gm = GameMode.SURVIVAL;
            }
            // Their own invisibility is not known here: it was only kept in memory, which the crash lost.
            restore(player, gm, null);
        }
        String back = player.getPersistentDataContainer().get(returnKey, PersistentDataType.STRING);
        if (back != null) {
            Location to = decode(back);
            if (to != null) player.teleport(to);
            player.getPersistentDataContainer().remove(returnKey);
        }
        for (ViewImpl v : views.values()) {
            Player seated = Bukkit.getPlayer(v.player);
            if (seated != null) player.hidePlayer(plugin, seated);
        }
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        ViewImpl v = of(e.getPlayer());
        if (v == null || e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        long now = Platform.tick();
        if (now - v.lastClickTick < 2) return; // attack and swing arrive together: one click
        v.lastClickTick = now;
        atClick(v, (x, y) -> v.content.click(v, x, y, Click.LEFT));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onRightClick(PlayerInteractEntityEvent e) {
        ViewImpl v = of(e.getPlayer());
        if (v == null) {
            if (views.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        // Holding the button repeats the click every 4 ticks; only a fresh press counts.
        long now = Platform.tick();
        boolean fresh = now - v.lastRightClickTick > 5;
        v.lastRightClickTick = now;
        if (fresh) atClick(v, (x, y) -> v.content.click(v, x, y, Click.RIGHT));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (views.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onScroll(PlayerItemHeldEvent e) {
        if (!views.containsKey(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true); // the client snaps back, so the next scroll is again ±1 from the real slot
        ViewImpl v = of(e.getPlayer());
        if (v == null) return;
        int delta = Math.floorMod(e.getNewSlot() - e.getPreviousSlot() + 4, 9) - 4;
        // The wheel moves one slot at a time; a bigger jump is a number key.
        if (Math.abs(delta) == 1) atClick(v, (x, y) -> v.content.scroll(v, x, y, delta));
        else if (delta != 0 && v.revealed) press(v, Key.hotbar(e.getNewSlot()));
    }

    @EventHandler
    public void onInput(PlayerInputEvent e) {
        ViewImpl v = views.get(e.getPlayer().getUniqueId());
        if (v == null) return;
        Input now = e.getInput();
        Input was = v.lastInput;
        v.lastInput = now;
        if (now.isSneak()) {
            Bukkit.getScheduler().runTask(plugin, () -> close(e.getPlayer(), CloseReason.PLAYER));
            return;
        }
        if (was == null || !v.revealed) return;
        edge(v, was.isForward(), now.isForward(), Key.UP);
        edge(v, was.isBackward(), now.isBackward(), Key.DOWN);
        edge(v, was.isLeft(), now.isLeft(), Key.LEFT);
        edge(v, was.isRight(), now.isRight(), Key.RIGHT);
        edge(v, was.isJump(), now.isJump(), Key.JUMP);
        edge(v, was.isSprint(), now.isSprint(), Key.SPRINT);
    }

    private static void edge(ViewImpl v, boolean before, boolean after, Key key) {
        if (before == after) return;
        v.held.put(key, after);
        v.content.key(v, key, after);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        if (!views.containsKey(e.getPlayer().getUniqueId())) return;
        e.setCancelled(true);
        ViewImpl v = of(e.getPlayer());
        if (v == null) return;
        press(v, Key.SWAP_HANDS);
    }

    /** A key with no hold to it: down and up at once. */
    private static void press(ViewImpl v, Key key) {
        v.content.key(v, key, true);
        v.content.key(v, key, false);
    }

    /** On the netty thread: the player pressed the drop key, which the client sends whatever their hand holds. */
    private void dropped(ViewImpl v) {
        nms.serverThread().execute(() -> {
            if (!v.closed && v.revealed && views.get(v.player) == v) press(v, Key.DROP);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (views.containsKey(e.getPlayer().getUniqueId())) e.setCancelled(true); // their hand looks empty; the real item stays put
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && views.containsKey(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        ViewImpl v = views.get(e.getPlayer().getUniqueId());
        if (v == null) return;
        e.setCancelled(true);
        String text = e.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (views.get(v.player) == v) v.content.chat(v, text);
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !views.containsKey(p.getUniqueId())) return;
        switch (e.getCause()) {
            case SUFFOCATION, CRAMMING, FALL -> e.setCancelled(true);
            // Hurt: get up and deal with it.
            default -> Bukkit.getScheduler().runTask(plugin, () -> close(p, CloseReason.INTERRUPTED));
        }
    }

    /** A server-placed pointer's player got off their armour stand some other way than sneaking (see onInput). */
    @EventHandler
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ViewImpl v = views.get(p.getUniqueId());
        if (v != null && e.getDismounted() == v.vehicle) Bukkit.getScheduler().runTask(plugin, () -> close(p, CloseReason.PLAYER));
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        ViewImpl v = views.get(e.getPlayer().getUniqueId());
        if (v == null) return;
        // Before the reveal they still stand on their own feet, and reveal() would undo a teleport by
        // seating them: so a real move closes a view still loading too. A nudge (a move correction) does
        // not matter either way, as getting up puts them back where they opened it.
        Location to = e.getTo(), at = v.revealed ? v.heldAt() : e.getFrom();
        if (to.getWorld() != at.getWorld() || to.distanceSquared(at) > 4) close(e.getPlayer(), CloseReason.INTERRUPTED);
    }

    /** The server turned [player] (a teleport or a respawn sets the rotation outright): not whole counts of their mouse. */
    private void holdMouse(Player player) {
        MouseSteps m = mice.get(player.getUniqueId());
        if (m != null) m.hold();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleported(PlayerTeleportEvent e) {
        holdMouse(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        holdMouse(e.getPlayer());
    }

    /**
     * Nothing the server pushes a held player with may move them (the client moves itself by any
     * velocity it is sent): it is sent as nothing instead. Not cancelled, or the server would keep
     * trying to send it and do so once they get up.
     */
    @EventHandler(ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        ViewImpl v = views.get(e.getPlayer().getUniqueId());
        if (v != null && v.clientPointer && v.revealed) e.setVelocity(new Vector());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        close(e.getPlayer(), CloseReason.INTERRUPTED);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        close(e.getEntity(), CloseReason.INTERRUPTED);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        try {
            close(e.getPlayer(), CloseReason.INTERRUPTED);
        } finally {
            pipeline.forget(e.getPlayer().getUniqueId());
            mice.remove(e.getPlayer().getUniqueId());
        }
    }

    /**
     * For /mcgui debug: put a view's pointer at (x, y) as if the mouse had moved there. A client-placed
     * pointer is where the player looks, so they are turned to look there too.
     */
    public void debugPoint(Player player, int x, int y) {
        ViewImpl v = views.get(player.getUniqueId());
        if (v == null) return;
        synchronized (v) {
            v.cursorX = Math.max(0, Math.min(v.canvas.width() - 1.0, x));
            v.cursorY = Math.max(0, Math.min(v.canvas.height() - 1.0, y));
            if (v.clientPointer) {
                float yaw = v.refYaw + (float) ((v.cursorX - v.canvas.width() / 2.0) / v.speed);
                float pitch = (float) ((v.cursorY - v.canvas.height() / 2.0) / v.speed);
                v.serverTurns.incrementAndGet();
                pipeline.sendNow(v.player, nms.rotate(yaw, false, pitch, false));
                pipeline.afterClientCaughtUp(player, v.serverTurns::incrementAndGet);
            } else {
                sendPointer(v);
            }
        }
        v.lastPointerX = (int) v.cursorX;
        v.lastPointerY = (int) v.cursorY;
        v.content.pointer(v, v.lastPointerX, v.lastPointerY);
    }
}
