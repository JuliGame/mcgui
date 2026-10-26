package ai.resourcepack.ui.framework.pack;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Puts the pack on ResourcePack AI's pack host, so players download it from there rather than from this
 * server: no second port to open and no address to type in. The same host and protocol as RP Engine's
 * {@code host.mode: upload}.
 *
 * <p>The host keys a pack by its SHA-1, so asking for one is cheap: a {@code touch} says whether it is
 * there already (and keeps it there), and only a pack it has never seen is sent. A server restarted with
 * the same pack uploads nothing. Blocking: {@link ResourcePack} runs it on a thread of its own.
 */
final class PackUploader {

    /** What the host takes at most. A bigger pack has to be served by the server itself. */
    static final long MAX_BYTES = 95L * 1024 * 1024;

    private static final int ATTEMPTS = 3;
    private static final long RETRY_MILLIS = 30_000L;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final String base;
    private final String agent;

    /**
     * @param base  the pack host, {@code https://packs.resourcepack.ai}
     * @param agent the User-Agent to send, which names the plugin and version
     */
    PackUploader(String base, String agent) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.agent = agent;
    }

    /** The link a player is sent for the pack with [sha1]. The host answers with the same one. */
    String linkFor(String sha1) {
        return base + "/h/" + sha1 + ".zip";
    }

    /** Makes sure the host has the pack, uploading it if not, and returns the link players download it from. */
    String ensure(byte[] bytes, String sha1) throws IOException, InterruptedException {
        for (int attempt = 1; ; attempt++) {
            int touched = touch(sha1);
            if (touched == 200) return linkFor(sha1);
            if (touched == 404) {
                int uploaded = upload(bytes, sha1);
                if (uploaded == 201 || uploaded == 200) return linkFor(sha1);
                if (uploaded != 429 && uploaded < 500) throw new IOException("the pack host refused the pack (HTTP " + uploaded + ")");
            } else if (touched != 429 && touched < 500) {
                throw new IOException("the pack host answered HTTP " + touched);
            }
            // Busy (429) or broken (5xx): worth another go, a little later.
            if (attempt >= ATTEMPTS) throw new IOException("the pack host is busy or down (HTTP " + touched + ")");
            Thread.sleep(RETRY_MILLIS);
        }
    }

    private int touch(String sha1) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/h/" + sha1 + "/touch"))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", agent)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int upload(byte[] bytes, String sha1) throws IOException, InterruptedException {
        if (bytes.length > MAX_BYTES) {
            throw new IOException("the pack is " + (bytes.length / 1024 / 1024) + " MB, and the pack host takes at most "
                    + (MAX_BYTES / 1024 / 1024) + " MB");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(linkFor(sha1)))
                // Generous: a pack full of sounds over a slow uplink.
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", agent)
                .header("Content-Type", "application/zip")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 400 || response.statusCode() == 413) {
            throw new IOException("the pack host refused the pack: " + response.body().trim());
        }
        return response.statusCode();
    }
}
