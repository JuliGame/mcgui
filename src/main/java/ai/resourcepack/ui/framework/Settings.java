package ai.resourcepack.ui.framework;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;

/** One reading of config.yml. */
public final class Settings {
    public enum PackMode { SERVE, EXTERNAL, OFF }

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
    public final double cursorSpeed;
    /** With the pack: the client draws the pointer itself every frame from where the player looks. */
    public final boolean clientPointer;
    /** Log each click's resolved spot and each pointer correction ({@code [pointer]} lines), for testing. */
    public final boolean debugPointer;
    public final int mapIdBase;
    /** Threads for all CPU-heavy work (0: the cores minus one); see {@link ai.resourcepack.ui.framework.api.Cpu}. */
    public final int cpuThreads;

    private Settings(FileConfiguration c) {
        packMode = PackMode.valueOf(c.getString("resource-pack.mode", "serve").toUpperCase(Locale.ROOT));
        packBind = c.getString("resource-pack.bind", "0.0.0.0");
        packPort = c.getInt("resource-pack.port", 25602);
        packPublicUrl = c.getString("resource-pack.public-url", "");
        packRequired = c.getBoolean("resource-pack.required", false);
        packPrompt = c.getString("resource-pack.prompt", "This server's screens need a small resource pack for full colour.");
        bytesPerSecond = Math.max(64 * 1024, c.getInt("screens.max-bytes-per-second-per-player", 16_000_000));
        viewDistance = c.getDouble("screens.view-distance", 48);
        reach = c.getDouble("screens.reach", 8);
        sharedCompression = c.getBoolean("screens.shared-compression", true);
        defaultFov = c.getInt("views.default-fov", 70);
        cursorSpeed = c.getDouble("views.cursor-speed", 8.0);
        clientPointer = c.getBoolean("views.client-pointer", true);
        debugPointer = c.getBoolean("views.debug-pointer", false);
        mapIdBase = c.getInt("map-id-base", 2_000_000_000);
        cpuThreads = Math.max(0, c.getInt("cpu-threads", 0));
    }

    public static Settings load(FileConfiguration c) {
        return new Settings(c);
    }
}
