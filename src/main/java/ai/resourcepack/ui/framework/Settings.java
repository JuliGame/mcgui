package ai.resourcepack.ui.framework;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;

/** One reading of config.yml. */
public final class Settings {
    public enum PackMode { UPLOAD, SERVE, EXTERNAL, OFF }

    public final PackMode packMode;
    public final String packBind;
    public final int packPort;
    public final String packPublicUrl;
    public final boolean packRequired;
    public final String packPrompt;
    public final int bytesPerSecond;
    public final double viewDistance;
    public final double reach;
    /** Compress each map patch once for every player who can take the same bytes, rather than once per player. */
    public final boolean sharedCompression;
    public final int defaultFov;
    /**
     * How far a view's pointer moves per mouse count, as a multiple of a desktop pointer at Windows' default
     * speed on a 1920-pixel-wide screen (one pixel per count); the view fills the window, so 1 crosses it in
     * 1920 counts. Each player's /mcgui pointer multiplies it.
     */
    public final double pointerSpeed;
    /** With the pack: the client draws the pointer itself every frame from where the player looks. */
    public final boolean clientPointer;
    /**
     * Hidden maps each client-placed view gets for hover layers (api/HoverLayers): pictures its client switches to by
     * itself when the pointer goes over an element. 0: none.
     */
    public final int hoverLayers;
    /** Log each click's resolved spot and each pointer correction ({@code [pointer]} lines), for testing. */
    public final boolean debugPointer;
    public final int mapIdBase;
    /** Threads for all CPU-heavy work (0: the cores minus one); see {@link ai.resourcepack.ui.framework.api.Cpu}. */
    public final int cpuThreads;

    private Settings(FileConfiguration c) {
        packMode = PackMode.valueOf(c.getString("resource-pack.mode", "upload").toUpperCase(Locale.ROOT));
        packBind = c.getString("resource-pack.bind", "0.0.0.0");
        packPort = c.getInt("resource-pack.port", 25602);
        packPublicUrl = c.getString("resource-pack.public-url", "");
        packRequired = c.getBoolean("resource-pack.required", false);
        packPrompt = c.getString("resource-pack.prompt", "This server's screens need a small resource pack for full colour.");
        bytesPerSecond = Math.max(64 * 1024, c.getInt("screens.max-bytes-per-second-per-player", 8_000_000));
        viewDistance = c.getDouble("screens.view-distance", 48);
        reach = c.getDouble("screens.reach", 8);
        sharedCompression = c.getBoolean("screens.shared-compression", true);
        defaultFov = c.getInt("views.default-fov", 70);
        pointerSpeed = Math.max(0.05, c.getDouble("views.pointer-speed", 1.0));
        clientPointer = c.getBoolean("views.client-pointer", true);
        hoverLayers = Math.max(0, Math.min(512, c.getInt("views.hover-layers", 0)));
        debugPointer = c.getBoolean("views.debug-pointer", false);
        mapIdBase = c.getInt("map-id-base", 2_000_000_000);
        cpuThreads = Math.max(0, c.getInt("cpu-threads", 0));
    }

    public static Settings load(FileConfiguration c) {
        return new Settings(c);
    }
}
