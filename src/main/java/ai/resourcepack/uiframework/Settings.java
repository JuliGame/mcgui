package ai.resourcepack.uiframework;

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
    public final int defaultFov;
    public final double cursorSpeed;
    /** With the pack: the client draws the pointer itself every frame from where the player looks. */
    public final boolean clientPointer;
    public final int mapIdBase;

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
        defaultFov = c.getInt("views.default-fov", 70);
        cursorSpeed = c.getDouble("views.cursor-speed", 8.0);
        clientPointer = c.getBoolean("views.client-pointer", true);
        mapIdBase = c.getInt("map-id-base", 2_000_000_000);
    }

    public static Settings load(FileConfiguration c) {
        return new Settings(c);
    }
}
