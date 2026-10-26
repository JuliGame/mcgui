package ai.resourcepack.ui.script;

import ai.resourcepack.ui.framework.api.Canvas;
import ai.resourcepack.ui.html.HtmlRenderer;
import ai.resourcepack.ui.html.Page;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Where scripts come from. The script engine (Rhino, Babel, the canvas) is a jar of its own,
 * {@code MCGUI-scripts-<version>.jar}, kept out of the plugin's so the plugin stays small and a server that never shows
 * a script never has it. The first page with a script fetches it from the CDN into {@code plugins/MCGUI/libs/}, checks
 * it against the SHA-256 this build of the plugin was made with ({@code script-engine.properties}), and loads it; until
 * then that page shows without its canvases moving, and draws again once they can. A server with no internet can put
 * the file there by hand. Where the engine's classes are already on the classpath (tests), they are used as they are.
 */
public final class ScriptEngine {
    private static final String FACTORY = "ai.resourcepack.ui.script.engine.Engine";
    private static final String CDN = "https://cdn.resourcepack.ai/";
    /** After a failed fetch, the next page with a script tries again no sooner than this. */
    private static final long RETRY_NANOS = 600_000_000_000L;

    private static Path libs;
    private static Logger log;
    private static volatile Script.Factory factory;
    private static URLClassLoader loader;
    private static boolean fetching;
    private static long failedAt;
    private static boolean failedOnce;
    private static final List<Consumer<Script.Factory>> waiting = new ArrayList<>();

    private ScriptEngine() {}

    /** Where the engine is kept ({@code <data folder>/libs}) and who hears about fetching it. On enable. */
    public static synchronized void configure(Path dataFolder, Logger logger) {
        libs = dataFolder.resolve("libs");
        log = logger;
    }

    /**
     * Starts [page]'s scripts for a session; null if it has none (then nothing about scripts happens at all). Before
     * the engine is here, what comes back waits for it, and asks [host] to draw again once it has started.
     */
    public static Script start(Page page, Canvas target, ReentrantLock lock, double scale, Logger logger, String name, Script.Host host) {
        if (!page.scripted()) return null;
        Script.Factory f = factory;
        if (f != null) return f.start(page, target, lock, scale, logger, name, host);
        Waiting w = new Waiting(page);
        whenReady(ready -> {
            if (w.stopped) return;
            Script real = ready.start(page, target, lock, scale, logger, name, host);
            w.real = real;
            if (w.stopped && real != null) real.stop();
            else host.redraw();
        });
        return w;
    }

    /** Stops every script and lets go of the engine (the plugin is disabled). */
    public static synchronized void shutdown() {
        Script.Factory f = factory;
        factory = null;
        waiting.clear();
        if (f != null) f.shutdown();
        if (loader != null) {
            try {
                loader.close();
            } catch (IOException ignored) {
                // Closing is tidying up.
            }
            loader = null;
        }
    }

    private static void whenReady(Consumer<Script.Factory> then) {
        Script.Factory ready;
        synchronized (ScriptEngine.class) {
            ready = factory;
            if (ready == null) {
                waiting.add(then);
                if (!fetching && (failedAt == 0 || System.nanoTime() - failedAt > RETRY_NANOS)) {
                    fetching = true;
                    Thread t = new Thread(ScriptEngine::fetch, "MCGUI script engine");
                    t.setDaemon(true);
                    t.start();
                }
                return;
            }
        }
        then.accept(ready);
    }

    /** Finds, fetches or checks the engine, loads it, and hands it to whoever waits. Its own thread. */
    private static void fetch() {
        Script.Factory made = null;
        try {
            made = load();
        } catch (Exception | LinkageError e) {
            synchronized (ScriptEngine.class) {
                failedAt = System.nanoTime();
                if (!failedOnce || log == null) {
                    failedOnce = true;
                    if (log != null) log.log(Level.WARNING, "Pages with scripts show without them: " + e.getMessage(), e instanceof IOException ? null : e);
                }
            }
        }
        List<Consumer<Script.Factory>> ready;
        synchronized (ScriptEngine.class) {
            fetching = false;
            factory = made;
            ready = new ArrayList<>(waiting);
            if (made != null) waiting.clear();
        }
        if (made == null) return;
        for (Consumer<Script.Factory> w : ready) {
            try {
                w.accept(made);
            } catch (RuntimeException e) {
                if (log != null) log.log(Level.WARNING, "A script could not start", e);
            }
        }
    }

    private static Script.Factory load() throws Exception {
        // On the classpath already (tests, a development build): no jar to fetch.
        try {
            return make(Class.forName(FACTORY, true, ScriptEngine.class.getClassLoader()));
        } catch (ClassNotFoundException notHere) {
            // The usual case: the engine is a jar of its own.
        }
        Properties info = new Properties();
        try (InputStream in = ScriptEngine.class.getResourceAsStream("/script-engine.properties")) {
            if (in == null) throw new IOException("this build of MC GUI names no script engine");
            info.load(in);
        }
        String version = info.getProperty("version"), sha = info.getProperty("sha256");
        if (version == null || sha == null || libs == null) throw new IOException("this build of MC GUI names no script engine");
        String file = "MCGUI-scripts-" + version + ".jar";
        Path jar = libs.resolve(file);
        if (!Files.isRegularFile(jar) || !sha.equalsIgnoreCase(sha256(jar))) {
            Files.createDirectories(libs);
            if (log != null) log.info("Fetching the script engine for pages with scripts (" + file + ", once)");
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create(CDN + file)).timeout(Duration.ofSeconds(120)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                throw new IOException("could not fetch " + CDN + file + " (HTTP " + res.statusCode() + "). Download it into " + libs + " by hand");
            }
            byte[] body = res.body();
            String got = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            // The engine runs on the server: anything but the very file this build was made with is refused.
            if (!sha.equalsIgnoreCase(got)) throw new IOException(CDN + file + " is not the file this build of MC GUI was made with (its SHA-256 differs)");
            Path part = libs.resolve(file + ".part");
            Files.write(part, body);
            Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        // Older engines are of older builds: nothing loads them.
        try (Stream<Path> old = Files.list(libs)) {
            old.filter(p -> p.getFileName().toString().startsWith("MCGUI-scripts-") && !p.getFileName().toString().equals(file)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Left for next time.
                }
            });
        }
        URLClassLoader l = new URLClassLoader("MCGUI scripts", new URL[]{jar.toUri().toURL()}, ScriptEngine.class.getClassLoader());
        Script.Factory f = make(Class.forName(FACTORY, true, l));
        synchronized (ScriptEngine.class) {
            loader = l;
        }
        if (log != null) log.info("Script engine " + version + " ready");
        return f;
    }

    private static Script.Factory make(Class<?> type) throws ReflectiveOperationException {
        return (Script.Factory) type.getDeclaredConstructor().newInstance();
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    /** A page's scripts while the engine is on its way: nothing happens, and the page shows as it is. */
    private static final class Waiting implements Script {
        private final Page page;
        volatile Script real;
        volatile boolean stopped;

        Waiting(Page page) {
            this.page = page;
        }

        @Override
        public Page page() {
            return page;
        }

        @Override
        public void stop() {
            stopped = true;
            Script r = real;
            if (r != null) r.stop();
        }

        @Override
        public void pointer(double x, double y) {
            Script r = real;
            if (r != null) r.pointer(x, y);
        }

        @Override
        public void click(double x, double y, boolean right) {
            Script r = real;
            if (r != null) r.click(x, y, right);
        }

        @Override
        public void key(String key, String code, boolean down) {
            Script r = real;
            if (r != null) r.key(key, code, down);
        }

        @Override
        public void placed(HtmlRenderer.Frame frame) {
            Script r = real;
            if (r != null) r.placed(frame);
        }

        @Override
        public BufferedImage over(BufferedImage img, int x, int y) {
            Script r = real;
            return r != null ? r.over(img, x, y) : img;
        }
    }
}
