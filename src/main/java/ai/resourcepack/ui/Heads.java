package ai.resourcepack.ui;

import ai.resourcepack.ui.html.res.Picture;
import ai.resourcepack.ui.html.res.Pictures;
import ai.resourcepack.ui.framework.platform.Platform;
import ai.resourcepack.ui.framework.platform.Threads;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Players' faces for pages: {@code <img src="head:{player}">}, or any name or UUID after {@code head:}. The
 * face is the front of the skin's head with its hat layer over it, 8 x 8 pixels, drawn crisp at any size.
 * An online player's skin is the one the server knows (a skin plugin's too); anybody else's is looked up
 * by name. Until it arrives, and for a name with no skin, a plain face stands in; the pages showing one
 * are drawn again when it comes.
 */
final class Heads {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}");
    /** How long a face is kept before it is looked up again, and how long one that could not be found is left alone. */
    private static final long KEEP_MILLIS = TimeUnit.MINUTES.toMillis(30), RETRY_MILLIS = TimeUnit.MINUTES.toMillis(5);
    /** Skins only ever come from the game's own texture server: a profile cannot make the server fetch anything else. */
    private static final String SKIN_HOST = "textures.minecraft.net";
    static final Picture STAND_IN = Pictures.of(standIn());

    private record Face(Picture picture, long until) {}

    private final MCGUIPlugin plugin;
    private final Runnable arrived;
    private final Map<String, Face> faces = new ConcurrentHashMap<>();
    private final Map<String, Boolean> asked = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** [arrived] runs on the server thread whenever a face comes in, so pages showing it are drawn again. */
    Heads(MCGUIPlugin plugin, Runnable arrived) {
        this.plugin = plugin;
        this.arrived = arrived;
    }

    /** The picture for {@code head:<name or uuid>}; any thread. A stand-in until the face has been fetched. */
    Picture image(String uri) {
        String who = uri.substring(uri.indexOf(':') + 1).replaceFirst("^//", "").trim();
        if (!NAME.matcher(who).matches() && !ID.matcher(who).matches()) return STAND_IN;
        String key = who.toLowerCase(Locale.ROOT);
        Face face = faces.get(key);
        if (face == null || face.until < System.currentTimeMillis()) fetch(key);
        return face == null ? STAND_IN : face.picture;
    }

    /** Looks [who] up and fetches their skin, once at a time. */
    private void fetch(String who) {
        if (asked.putIfAbsent(who, true) != null || !plugin.isEnabled()) return;
        // The player and their profile are read on the server thread (on Folia, theirs); the lookup and the download are not.
        Threads.global(plugin, () -> {
            Player online = ID.matcher(who).matches() ? Bukkit.getPlayer(uuid(who)) : Bukkit.getPlayerExact(who);
            if (online == null || Threads.owns(online)) lookUp(who, online);
            else Threads.on(plugin, online, () -> lookUp(who, online));
        });
    }

    private void lookUp(String who, Player online) {
        PlayerProfile known = online == null ? null : Platform.profile(online);
        String name = online != null ? online.getName() : NAME.matcher(who).matches() ? who : null;
        UUID id = online == null && name == null ? uuid(who) : null;
        Threads.async(plugin, () -> {
            Picture picture = null;
            try {
                URL skin = known == null ? null : known.getTextures().getSkin();
                if (skin == null) {
                    // Looked up by name (or UUID) at Mojang, whether or not this server checks accounts.
                    PlayerProfile looked = Bukkit.createPlayerProfile(id, name).update().get(15, TimeUnit.SECONDS);
                    if (looked != null) skin = looked.getTextures().getSkin();
                }
                if (skin != null) picture = face(skin);
            } catch (Exception e) {
                if (plugin.isEnabled() && plugin.debug()) plugin.getLogger().info("No face for " + who + ": " + e);
            }
            long now = System.currentTimeMillis();
            faces.put(who, picture != null ? new Face(picture, now + KEEP_MILLIS) : new Face(STAND_IN, now + RETRY_MILLIS));
            asked.remove(who);
            if (picture != null && plugin.isEnabled()) Threads.global(plugin, arrived);
        });
    }

    /** The face on the skin at [url]: the head's front, its hat layer drawn over it. */
    private Picture face(URL url) throws Exception {
        URI uri = url.toURI();
        if (!SKIN_HOST.equalsIgnoreCase(uri.getHost())) return null;
        HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) return null;
        BufferedImage skin = ImageIO.read(new ByteArrayInputStream(res.body()));
        if (skin == null || skin.getWidth() < 64 || skin.getHeight() < 32) return null;
        // A skin may be drawn larger than 64 wide (HD skins): every coordinate scales with it.
        int u = skin.getWidth() / 64;
        BufferedImage out = new BufferedImage(8 * u, 8 * u, BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.drawImage(skin.getSubimage(8 * u, 8 * u, 8 * u, 8 * u), 0, 0, null);
        g.drawImage(skin.getSubimage(40 * u, 8 * u, 8 * u, 8 * u), 0, 0, null);
        g.dispose();
        return Pictures.of(out);
    }

    private static UUID uuid(String s) {
        String hex = s.replace("-", "");
        return new UUID(Long.parseUnsignedLong(hex.substring(0, 16), 16), Long.parseUnsignedLong(hex.substring(16), 16));
    }

    /** A plain face: grey, darker hair and eyes. Ours, so nothing of the game's is in the jar. */
    private static BufferedImage standIn() {
        String[] art = {
                "HHHHHHHH",
                "HHHHHHHH",
                "HSSSSSSH",
                "SSSSSSSS",
                "SEWSSWES",
                "SSSSSSSS",
                "SSSMMSSS",
                "SSSSSSSS",
        };
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                img.setRGB(x, y, switch (art[y].charAt(x)) {
                    case 'H' -> 0xFF4A4A50;
                    case 'E' -> 0xFF2A2A30;
                    case 'W' -> 0xFFE8E8E8;
                    case 'M' -> 0xFF6E6E74;
                    default -> 0xFF9A9AA0;
                });
            }
        }
        return img;
    }
}
