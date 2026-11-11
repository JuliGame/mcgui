package ai.resourcepack.ui.framework.pack;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Which of a sibling plugin's packs replace any of MC GUI's files, and so bury ours when a game takes them after it.
 * A sibling only says a pack is its own ({@link Siblings}), and the shared library that says so cannot be changed
 * from here, so the pack's own contents tell: each pack sent to a player is seen on its way out (Pipeline.packPushes),
 * and the first time one of a sibling's turns up, it is fetched from the link the client got and its file names read.
 * Dialog Engine's drawn-dialog pack carries our text shader and {@code position_tex_color}; RP Engine's packs usually
 * carry none of ours (it merges a text shader only into a bundle that already has one, and then it does count).
 * Verdicts are kept by the pack's hash, so each version of a pack is fetched once. A pack whose push was not seen, or
 * that cannot be fetched or read, counts as replacing ours: a page costs a resource reload it may not have needed,
 * never a page drawn without the pack.
 */
public final class SiblingShaders implements AutoCloseable {
    /** The most of a sibling's pack read: a bigger one counts as replacing ours. */
    private static final long MAX_BYTES = 512L << 20;

    /** Our files under assets/minecraft/, as a game finds them (overlay directories taken off). */
    private final Set<String> ours;
    private final Logger logger;
    /** The last push of each pack id to each player. */
    private final Map<UUID, Map<UUID, Push>> pushes = new ConcurrentHashMap<>();
    /** By pack key: whether it replaces any of ours, once fetched. */
    private final Map<String, CompletableFuture<Boolean>> verdicts = new ConcurrentHashMap<>();
    private final ExecutorService fetcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MCGUI-sibling-packs");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private record Push(String url, String hash) {
        String key() {
            return hash.isEmpty() ? url : hash;
        }
    }

    public SiblingShaders(byte[] ourZip, Logger logger) throws IOException {
        this.ours = minecraftFiles(new ZipInputStream(new ByteArrayInputStream(ourZip)));
        this.logger = logger;
    }

    /** A pack [pack] was sent to [player] from [url] with the sha1 [hash] (netty thread). */
    public void pushed(UUID player, UUID pack, String url, String hash) {
        pushes.computeIfAbsent(player, k -> new ConcurrentHashMap<>()).put(pack, new Push(url, hash == null ? "" : hash));
    }

    /** What the pack [player] was last sent as [pack] goes by: its hash (or link) as sent, or its id if its push was not seen. */
    public String key(UUID player, UUID pack) {
        Push p = push(player, pack);
        return p != null ? p.key() : "id:" + pack;
    }

    /**
     * Whether the pack [player] was last sent as [pack] replaces any of our files: done at once if it was looked at
     * before, else once it has been fetched. True when that cannot be told.
     */
    public CompletableFuture<Boolean> replaces(UUID player, UUID pack) {
        Push p = push(player, pack);
        if (p == null) return CompletableFuture.completedFuture(true);
        String key = p.key();
        CompletableFuture<Boolean> verdict = verdicts.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> fetch(p), fetcher));
        return verdict.handle((replaces, failed) -> {
            if (replaces != null) return replaces;
            // Not cached: the next time it is taken, it is fetched again.
            verdicts.remove(key, verdict);
            return true;
        });
    }

    public void left(UUID player) {
        pushes.remove(player);
    }

    @Override
    public void close() {
        fetcher.shutdownNow();
        http.shutdownNow();
    }

    private Push push(UUID player, UUID pack) {
        Map<UUID, Push> sent = pushes.get(player);
        return sent == null ? null : sent.get(pack);
    }

    private boolean fetch(Push p) {
        try {
            HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(URI.create(p.url())).timeout(Duration.ofSeconds(60))
                            .header("User-Agent", PackFetch.USER_AGENT).GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("mcgui-sibling", ".zip");
            try {
                try (InputStream body = response.body()) {
                    if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                    java.nio.file.Files.copy(new Capped(body), tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                // Read by its central directory, as the game does: a protected pack (ItemsAdder's) scrambles its local
                // headers, and read front to back it shows a hundred of its ten thousand files.
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(tmp.toFile())) {
                    return replaces(ours, zip);
                }
            } finally {
                java.nio.file.Files.deleteIfExists(tmp);
            }
        } catch (IOException | IllegalArgumentException e) {
            logger.info("Could not read a pack another plugin sent (" + e.getMessage() + "), so it counts as replacing MC GUI's"
                    + " shaders: a page opened after it sends MC GUI's pack again first.");
            throw new java.util.concurrent.CompletionException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CompletionException(e);
        }
    }

    /** Whether the pack [zip] has any of [ours] (files as {@link #minecraftFiles} gives them), in its root or any overlay. */
    static boolean replaces(Set<String> ours, ZipInputStream zip) throws IOException {
        for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
            if (!e.isDirectory() && ours.contains(logical(e.getName()))) return true;
        }
        return false;
    }

    /** Whether the pack [zip] has any of [ours], read by its central directory. */
    static boolean replaces(Set<String> ours, java.util.zip.ZipFile zip) {
        return zip.stream().anyMatch(e -> !e.isDirectory() && ours.contains(logical(e.getName())));
    }

    /** The files of the pack [zip] under assets/minecraft/, as a game finds them: overlay directories taken off. */
    static Set<String> minecraftFiles(ZipInputStream zip) throws IOException {
        Set<String> files = new HashSet<>();
        for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
            String name = logical(e.getName());
            if (!e.isDirectory() && name.startsWith("assets/minecraft/")) files.add(name);
        }
        return files;
    }

    /** [name] as a game finds it: a file in an overlay (a directory at the root holding assets/) without the overlay's directory. */
    static String logical(String name) {
        if (name.startsWith("assets/")) return name;
        int slash = name.indexOf('/');
        return slash > 0 && name.startsWith("assets/", slash + 1) ? name.substring(slash + 1) : name;
    }

    /** Stops reading a pack bigger than {@link #MAX_BYTES}. */
    private static final class Capped extends FilterInputStream {
        private long read;

        Capped(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count(n);
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > MAX_BYTES) throw new IOException("bigger than " + (MAX_BYTES >> 20) + " MB");
        }
    }
}
