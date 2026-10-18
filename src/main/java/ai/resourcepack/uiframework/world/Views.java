package ai.resourcepack.uiframework.world;

import ai.resourcepack.uiframework.Settings;
import ai.resourcepack.uiframework.api.Canvas;
import ai.resourcepack.uiframework.api.Click;
import ai.resourcepack.uiframework.api.CloseReason;
import ai.resourcepack.uiframework.api.Key;
import ai.resourcepack.uiframework.api.Pointer;
import ai.resourcepack.uiframework.api.View;
import ai.resourcepack.uiframework.api.ViewContent;
import ai.resourcepack.uiframework.api.ViewOptions;
import ai.resourcepack.uiframework.net.MapSender;
import ai.resourcepack.uiframework.net.Nms;
import ai.resourcepack.uiframework.net.Pipeline;
import ai.resourcepack.uiframework.pack.ResourcePack;
import ai.resourcepack.uiframework.pack.Shaders;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WeatherType;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
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
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Fullscreen views. A seated player rides an invisible armour stand with their camera locked onto an
 * invisible mannequin whose eyes look straight at a wall of maps only they can see. A passenger keeps
 * sending its rotation, so turning the mouse moves a pointer; the attack and use keys click, the wheel
 * scrolls (the hotbar snaps back), movement keys are keys, and sneaking gets up.
 *
 * <p>Players with the pack see the pointer as a text display in front of the picture, which their
 * client glides between updates at its own frame rate; players without it get it drawn into the maps.
 * The mouse is read off the wire as each packet arrives and the pointer's move goes straight back,
 * rather than both waiting for the next tick; the client only sends its rotation 20 times a second,
 * whatever the server's tick rate, so that is as fresh as the pointer can be.
 *
 * <p>With the pack the shader stretches the picture to fill the window (see {@link Shaders}), so it is
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
     * Size of the Interaction round a client-placed pointer's eye, in blocks. Its nearest face must be
     * nearer than any block face can be (the flagged coordinate can be 3/128 of a block from one), so a
     * click hits it whichever way the player looks.
     */
    private static final float CLIENT_TARGET = 0.02f;
    /** The widest turn either side of the picture's centre a client-placed pointer may need: below ±180°. */
    private static final double CLIENT_MAX_YAW = 150;
    /** The steepest: below the client's own ±90° limit, with room for a fast flick past the edge. */
    private static final double CLIENT_MAX_PITCH = 75;

    private final Plugin plugin;
    private final Settings settings;
    private final Nms nms;
    private final Pipeline pipeline;
    private final Predicate<UUID> hasPack;
    private final int firstMapId;
    private final MapSender sender;
    private final Map<UUID, ViewImpl> views = new ConcurrentHashMap<>();
    private final NamespacedKey seatKey, returnKey, fovKey;
    private final EntityType mannequin;

    public Views(Plugin plugin, Settings settings, Nms nms, Pipeline pipeline, Predicate<UUID> hasPack, MapSender sender, int firstMapId) {
        this.plugin = plugin;
        this.settings = settings;
        this.nms = nms;
        this.pipeline = pipeline;
        this.hasPack = hasPack;
        this.sender = sender;
        this.firstMapId = firstMapId;
        this.seatKey = new NamespacedKey(plugin, "seated");
        this.returnKey = new NamespacedKey(plugin, "return-to");
        this.fovKey = new NamespacedKey(plugin, "fov");
        // 1.21.9+: a camera with a player's eye height (see spawnCamera).
        this.mannequin = Registry.ENTITY_TYPE.get(NamespacedKey.minecraft("mannequin"));
    }

    public final class ViewImpl implements View {
        final UUID player;
        final ViewContent content;
        final Canvas canvas;
        final MapWall wall;
        final ViewOptions options;
        final boolean withPack;
        /** The player's client places the pointer (and the picture) itself, from where their own eye looks. */
        final boolean clientPointer;
        /** Pixels per degree of turn: for a client-placed pointer, the one the shader is told too. */
        final double speed;
        /** The yaw that looks straight at the picture's centre. */
        final float refYaw;
        final ArmorStand vehicle;
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
        boolean pitchReset;
        /** A client-placed pointer: the player's whole turn from the picture's centre, not wrapped (degrees). */
        double turnYaw;
        /** A turn back inside the picture has been sent and the client has not yet answered the ping after it. */
        volatile boolean correcting;
        /**
         * Clicks seen on the wire, and how many of them a rotation has followed (under the view's lock): a
         * client sends a click before the same tick's rotation, so the rotation after it is where the
         * pointer was drawn when it was made. [clickX], [clickY]: the cursor that rotation gave.
         */
        int clicksSeen, clicksPlaced;
        double clickX, clickY;
        /** Prototype measurement: the cursor when the last click went by, before its rotation. */
        double preClickX, preClickY;
        /** Clicks handled before the rotation after them had arrived; run once it has. */
        final List<java.util.function.BiConsumer<Integer, Integer>> waitingClicks = new ArrayList<>();
        Input lastInput;
        final Map<Key, Boolean> held = new EnumMap<>(Key.class);
        long lastClickTick = -10, lastRightClickTick = -10;
        Pointer shape = Pointer.ARROW;
        Pointer shownShape = null;
        int lastPointerX = -1, lastPointerY = -1;
        /** A pointer report is waiting for the server thread (see turned). */
        final java.util.concurrent.atomic.AtomicBoolean reportQueued = new java.util.concurrent.atomic.AtomicBoolean();
        boolean pinged;
        volatile boolean revealed, closed;
        volatile boolean caughtUp;

        ViewImpl(Player p, ViewContent content, Canvas canvas, MapWall wall, ViewOptions options, boolean withPack, boolean clientPointer,
                 double speed, ArmorStand vehicle, LivingEntity camera, Interaction target, TextDisplay pointer, Location returnTo, Location cameraAt) {
            this.player = p.getUniqueId();
            this.content = content;
            this.canvas = canvas;
            this.wall = wall;
            this.options = options;
            this.withPack = withPack;
            this.clientPointer = clientPointer;
            this.speed = speed;
            this.refYaw = cameraAt.getYaw();
            this.vehicle = vehicle;
            this.camera = camera;
            this.target = target;
            this.pointer = pointer;
            this.previousMode = p.getGameMode();
            this.previousWeather = p.getPlayerWeather();
            this.previousInvisibility = p.getPotionEffect(PotionEffectType.INVISIBILITY);
            this.returnTo = returnTo;
            this.privateView = new PrivateView(wall, cameraAt, !withPack);
            this.openedAt = Bukkit.getCurrentTick();
            this.deadline = openedAt + REVEAL_TIMEOUT;
            this.pointerId = pointer == null ? -1 : pointer.getEntityId();
            this.cursorX = canvas.width() / 2.0;
            this.cursorY = canvas.height() / 2.0;
            this.clickX = cursorX;
            this.clickY = cursorY;
        }

        List<Entity> entities() {
            List<Entity> out = new ArrayList<>(List.of(vehicle, target));
            if (camera != null) out.add(camera);
            if (pointer != null) out.add(pointer);
            return out;
        }

        @Override public Player player() { return Bukkit.getPlayer(player); }
        @Override public ViewContent content() { return content; }
        @Override public int pointerX() { return (int) cursorX; }
        @Override public int pointerY() { return (int) cursorY; }
        @Override public Pointer pointer() { return shape; }
        @Override public void pointer(Pointer shape) { this.shape = shape == null ? Pointer.ARROW : shape; }
        @Override public boolean shown() { return revealed; }
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

    /** The player's FOV setting, as they told us with /uif fov; the server cannot read it. */
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

    public View open(Player player, ViewContent content, ViewOptions options) {
        close(player, CloseReason.CLOSED);
        Canvas canvas = content.canvas();
        boolean withPack = hasPack.test(player.getUniqueId());
        boolean clientPointer = withPack && settings.clientPointer;
        double fov = viewFov(player, withPack ? STRETCHED_FIT : 1.0);
        Location from = player.getLocation();
        MapWall wall = inFront(player, canvas.cols(), canvas.rows(), fov);
        if (wall == null) throw new IllegalStateException("There isn't enough room around " + player.getName() + " for a view");
        World world = wall.world;
        Location cam = wall.cameraLocation(fov);
        if (clientPointer) {
            // Where the picture really is no longer matters, only that the camera is flagged: so the middle
            // of the block the player's own eye is in (air, as they stand there), 3/128 off it along the
            // flagged axis. Every block face is then at least 0.27 away: more than the half-width of
            // the eye box the client tests for its in-wall overlay, and far more than the target round
            // the eye, which a click must hit before any block. The frames' block is plainly the camera's.
            Location eye = player.getEyeLocation();
            double x = Math.floor(eye.getX()) + 0.5, y = Math.floor(eye.getY()) + 0.5, z = Math.floor(eye.getZ()) + 0.5;
            if (wall.facing.getModX() != 0) x += Shaders.FLAG_PHASE;
            else z += Shaders.FLAG_PHASE;
            cam = new Location(world, x, y, z, cam.getYaw(), 0);
        }
        // Spawned unseen: the maps go out first, and the frames only appear once they can show them.
        wall.spawn(plugin, player, false, clientPointer ? stackBlock(cam) : null);
        double speed = clientPointer ? clientSpeed(canvas) : settings.cursorSpeed * canvas.height() / 768.0;

        // With a client-placed pointer the camera is the player's own eye, which turns with the mouse:
        // they sit exactly where it puts their eye on the flagged spot. Otherwise a camera entity looks at
        // the wall and they sit out of the way.
        LivingEntity camera = clientPointer ? null : spawnCamera(cam);
        Location seatAt = clientPointer ? cam.clone().subtract(0, eyeAboveSeat(player), 0)
                : cam.clone().subtract(cam.getDirection().multiply(1.5)).subtract(0, 1.6, 0);
        ArmorStand vehicle = world.spawn(seatAt, ArmorStand.class, a -> {
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
            d.text(Component.empty());
            d.setBackgroundColor(org.bukkit.Color.fromARGB(0));
            d.setShadowed(false);
            d.setBillboard(clientPointer ? Display.Billboard.FIXED : Display.Billboard.CENTER);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(clientPointer ? 0 : 1);
            d.setPersistent(false);
            d.setVisibleByDefault(false);
        });
        for (Entity e : camera == null ? List.of(vehicle, target) : List.of(camera, vehicle, target)) player.showEntity(plugin, e);

        ViewImpl view = new ViewImpl(player, content, canvas, wall, options, withPack, clientPointer, speed, vehicle, camera, target, pointer, from, cam);
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
            // Cosmetic on newer servers only.
        }
    }

    /**
     * How far a rider's eye is above the marker armour stand they ride: a marker carries its rider at its
     * own position, by a point 0.6 (scaled) above the player's feet, and a standing player's eye is 1.62
     * (a float, scaled) above their feet. As the client works it out, so the eye lands on the flagged spot.
     */
    private static double eyeAboveSeat(Player player) {
        org.bukkit.attribute.AttributeInstance attr = player.getAttribute(org.bukkit.attribute.Attribute.SCALE);
        float scale = attr == null ? 1f : (float) attr.getValue();
        return (double) (1.62f * scale) - 0.6 * scale;
    }

    /**
     * The block a client-placed view's frames all lie in, on its floor: the camera's own, so each frame's
     * culling box (half a block round it) holds the camera, which is half a block up in it.
     */
    private static int[] stackBlock(Location cam) {
        return new int[]{cam.getBlockX(), cam.getBlockY(), cam.getBlockZ()};
    }

    /**
     * Pixels per degree for a client-placed pointer: the configured speed, but fast enough that the
     * picture's edges are inside {@link #CLIENT_MAX_YAW} and {@link #CLIENT_MAX_PITCH} of its centre (the
     * pointer is where the camera looks, so an edge further round than that could never be reached).
     */
    private double clientSpeed(Canvas canvas) {
        double speed = settings.cursorSpeed * canvas.height() / 768.0;
        speed = Math.max(speed, canvas.width() / 2.0 / CLIENT_MAX_YAW);
        speed = Math.max(speed, canvas.height() / 2.0 / CLIENT_MAX_PITCH);
        return Math.min(speed, Shaders.CLIENT_POINTER_MAX_SPEED);
    }

    /**
     * A wall in front of [player] at eye level, far enough away to fill their view from about their
     * own block column. Its frames can be inside the ground: they are drawn in front of it.
     */
    private MapWall inFront(Player player, int cols, int rows, double fov) {
        BlockFace toward = player.getFacing();
        if (!MapWall.HORIZONTAL.contains(toward)) toward = BlockFace.NORTH;
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

    /** Every tick: switch waiting players to their view, and turn mouse movement into pointer movement. */
    public void tick() {
        int now = Bukkit.getCurrentTick();
        for (ViewImpl v : views.values()) {
            Player p = Bukkit.getPlayer(v.player);
            if (p == null) continue;
            if (!v.revealed) {
                if (now < v.deadline) {
                    // Written to the network is not yet shown: wait for the client to have handled the tiles too.
                    if (!v.pinged && sender.delivered(v.player, v.wall)) {
                        v.pinged = true;
                        pipeline.afterClientCaughtUp(p, () -> v.caughtUp = true);
                    }
                    if (!v.caughtUp) continue;
                }
                reveal(p, v);
            }
            // A client that never takes the teleport (another plugin cancelled it) must not leave the mouse dead.
            if (v.awaitingTeleport && now >= v.settleBy) v.awaitingTeleport = false;
            report(v);
            movePointer(v);
            if (now % 40 == 0) v.privateView.refresh(p);
        }
    }

    private void reveal(Player p, ViewImpl v) {
        // Before revealed, so turned() never reads a rotation from before the teleport below as the start.
        v.settleBy = Bukkit.getCurrentTick() + SETTLE_TIMEOUT;
        v.awaitingTeleport = true;
        v.revealed = true;
        // All in one tick, so the client applies them together with the camera switch.
        v.wall.showTo(plugin, p);
        v.privateView.open(plugin, p);
        if (v.pointer != null) {
            // Spawned for them where it belongs: the server's later moves of it never reach them (see Pipeline.watch).
            movePointer(v);
            p.showEntity(plugin, v.pointer);
            if (!v.clientPointer) sendPointer(v);
        }
        if (v.clientPointer) {
            // Put where riding will carry them, looking at the picture's centre, so mounting moves nothing
            // and their eye is on the flagged spot from the first frame. Their own eye stays the camera.
            Location seat = v.vehicle.getLocation().subtract(0, 0.6 * scaleOf(p), 0);
            seat.setYaw(v.refYaw);
            seat.setPitch(0);
            p.teleport(seat);
            v.vehicle.addPassenger(p);
        } else {
            p.teleport(v.vehicle.getLocation());
            v.vehicle.addPassenger(p);
            nms.setCamera(p, v.camera);
            // Again once everything has settled, in case the teleport and mount reset it.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (views.get(v.player) == v && p.isOnline()) nms.setCamera(p, v.camera);
            }, 5);
        }
        p.sendActionBar(Component.text("Sneak to leave", NamedTextColor.GRAY));
    }

    private static double scaleOf(Player player) {
        org.bukkit.attribute.AttributeInstance attr = player.getAttribute(org.bukkit.attribute.Attribute.SCALE);
        return attr == null ? 1.0 : (float) attr.getValue();
    }

    /**
     * The player turned their head to (yaw, pitch), on the netty thread as the packet arrives: move the
     * cursor by how far they turned, and the pointer with it.
     */
    private void turned(ViewImpl v, float yaw, float pitch) {
        synchronized (v) {
            if (!v.revealed || v.closed) return;
            // Until the client has taken the teleport to the seat, its rotation is from before it, and
            // starting from that would throw the pointer by the difference the moment the view appears.
            if (v.awaitingTeleport) return;
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
        if (v.clicksPlaced != v.clicksSeen) {
            // The rotation after a click: where its pointer was drawn.
            v.clicksPlaced = v.clicksSeen;
            v.clickX = cx;
            v.clickY = cy;
            if (!v.waitingClicks.isEmpty()) {
                List<java.util.function.BiConsumer<Integer, Integer>> run = new ArrayList<>(v.waitingClicks);
                v.waitingClicks.clear();
                int px = (int) cx, py = (int) cy;
                nms.serverThread().execute(() -> {
                    if (v.closed || views.get(v.player) != v) return;
                    for (java.util.function.BiConsumer<Integer, Integer> click : run) click.accept(px, py);
                });
            }
        }
        if (moved) queueReport(v);
        double backX = Math.max(0, Math.min(w - 1.0, whole)) - whole, backY = cy - y;
        if (!v.correcting && (Math.abs(backX) > 0.5 || Math.abs(backY) > 0.5)) {
            Player p = Bukkit.getPlayer(v.player);
            if (p == null) return;
            v.correcting = true;
            float dyaw = (float) (backX / v.speed), dpitch = (float) (backY / v.speed);
            plugin.getLogger().info("[pointer] past the edge: turning back " + dyaw + "," + dpitch);
            pipeline.sendNow(v.player, nms.rotate(dyaw, true, dpitch, true));
            pipeline.afterClientCaughtUp(p, () -> v.correcting = false);
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
     * Runs [click] with where the pointer was when it was made. For a client-placed pointer that is the
     * cursor from the rotation after the click on the wire, which the client sent the same tick (see
     * {@link ViewImpl#clicksSeen}); if that has not arrived yet, [click] waits for it. Server thread.
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
                // Prototype measurement: where the cursor was before the click's own rotation (what using it would miss by).
                plugin.getLogger().info("[pointer] click at " + x + "," + y + ", before its rotation " + (int) v.preClickX + "," + (int) v.preClickY);
            } else {
                plugin.getLogger().info("[pointer] click waits for its rotation, before it " + (int) v.preClickX + "," + (int) v.preClickY);
                v.waitingClicks.add(click);
                return;
            }
        }
        click.accept(x, y);
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
        Pointer shape = v.shape;
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
            pointer.text(shape == Pointer.HIDDEN ? Component.empty()
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
                canvas = null;
            }
            if (canvas == null || canvas.cols() != v.wall.cols || canvas.rows() != v.wall.rows) canvas = v.canvas;
            if (v.withPack) out.add(new MapSender.Assignment(p, v.wall, canvas, v.clientPointer ? Canvas.Encoding.HI_TILED : Canvas.Encoding.HI_OWN, null));
            else out.add(new MapSender.Assignment(p, v.wall, canvas, Canvas.Encoding.PLAIN,
                    v.revealed ? new MapSender.PointerSpot((int) v.cursorX, (int) v.cursorY, v.shape) : null));
        }
    }

    public void close(Player player, CloseReason reason) {
        ViewImpl v = views.remove(player.getUniqueId());
        if (v == null) return;
        v.closed = true;
        pipeline.unwatch(player.getUniqueId());
        // The player first, and the content after: a content that throws must never leave them seated,
        // invisible and hidden, with the view already gone so that nothing could close it again.
        if (player.isOnline()) {
            nms.setCamera(player, player);
            v.vehicle.removePassenger(player);
        }
        for (Entity e : v.entities()) e.remove();
        PotionEffect own = v.previousInvisibility;
        if (own != null && !own.isInfinite()) {
            // Sitting does not stop its clock.
            int left = own.getDuration() - (Bukkit.getCurrentTick() - v.openedAt);
            own = left > 0 ? own.withDuration(left) : null;
        }
        restore(player, v.previousMode, own);
        if (v.previousWeather != null) player.setPlayerWeather(v.previousWeather);
        else player.resetPlayerWeather();
        // Players still seated stay hidden from everyone (see open), whatever this view hid of them.
        v.privateView.close(plugin, player, e -> e instanceof Player other && views.containsKey(other.getUniqueId()));
        v.wall.despawn();
        // Not when they have already gone to another world (the move closed the view): that is where they were sent.
        if (player.isOnline() && player.getWorld() == v.wall.world) player.teleport(v.returnTo);
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
        long now = Bukkit.getCurrentTick();
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
        long now = Bukkit.getCurrentTick();
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
        // The wheel moves one slot at a time; a bigger jump is a number key, which should not scroll.
        if (Math.abs(delta) == 1) atClick(v, (x, y) -> v.content.scroll(v, x, y, delta));
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
        v.content.key(v, Key.SWAP_HANDS, true);
        v.content.key(v, Key.SWAP_HANDS, false);
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
    public void onChat(AsyncChatEvent e) {
        ViewImpl v = views.get(e.getPlayer().getUniqueId());
        if (v == null) return;
        e.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(e.message());
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
        Location to = e.getTo(), at = v.revealed ? v.vehicle.getLocation() : e.getFrom();
        if (to.getWorld() != at.getWorld() || to.distanceSquared(at) > 4) close(e.getPlayer(), CloseReason.INTERRUPTED);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        close(e.getPlayer(), CloseReason.INTERRUPTED);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        close(e.getPlayer(), CloseReason.INTERRUPTED);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        try {
            close(e.getPlayer(), CloseReason.INTERRUPTED);
        } finally {
            pipeline.forget(e.getPlayer().getUniqueId());
        }
    }

    /**
     * For /uif debug: put a view's pointer at (x, y) as if the mouse had moved there. A client-placed
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
                pipeline.sendNow(v.player, nms.rotate(yaw, false, pitch, false));
            } else {
                sendPointer(v);
            }
        }
        v.lastPointerX = (int) v.cursorX;
        v.lastPointerY = (int) v.cursorY;
        v.content.pointer(v, v.lastPointerX, v.lastPointerY);
    }
}
