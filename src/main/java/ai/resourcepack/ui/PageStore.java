package ai.resourcepack.ui;

import ai.resourcepack.ui.html.Page;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The pages folder: every .html in it (subfolders too) is a page, named by its path without the
 * extension ("menu", "shop/ranks"). Pages load on first use and load again whenever a file they
 * read changes, so editing the HTML (or its CSS, or an image) shows up in game in a second.
 *
 * <p>Loading reads files and can fetch from the web, so it happens on the store's own threads and
 * never on the server thread: {@link #load} hands back a future, one per page however many ask.
 */
public final class PageStore {
    private final Path root;
    private final Page.Loader loader;
    private final Logger logger;
    private final ExecutorService loaders;
    /** Each page's load, finished or not; it completes with null when the page could not be loaded. */
    private final Map<String, CompletableFuture<Loaded>> pages = new ConcurrentHashMap<>();
    private final AtomicBoolean checking = new AtomicBoolean();
    /** Told the name of every page that reloads. */
    public volatile Consumer<String> onReload = name -> {};
    /**
     * Page files that last failed to read as UTF-8, with their stamp then. One read so is taken for a
     * file caught mid-write; read so again with the stamp unchanged, the file is what it is (Windows-1252,
     * say) and is read leniently, as a browser would show it.
     */
    private final Map<Path, Long> undecodable = new ConcurrentHashMap<>();
    /** How long a first load waits before reading a file that was not UTF-8 again. */
    private static final long REREAD_MS = 1000;

    /** A loaded page and the modification times of the files it read; [retry]: the last reload failed, try again regardless. */
    private record Loaded(Page page, Map<Path, Long> stamps, boolean retry) {}

    public PageStore(Path root, Page.Loader loader, Logger logger) {
        this.root = root;
        this.loader = loader;
        this.logger = logger;
        AtomicInteger n = new AtomicInteger();
        // Two at most; gone after a minute with nothing to load, so a server at rest keeps none.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "UIEngine-load-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        this.loaders = pool;
    }

    public Path root() {
        return root;
    }

    /** "menu", "/menu.html", "shop/ranks" all name a page; null for one that escapes the folder. */
    public String normalise(String name) {
        String n = name.trim().replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        if (n.toLowerCase(Locale.ROOT).endsWith(".html")) n = n.substring(0, n.length() - 5);
        else if (n.toLowerCase(Locale.ROOT).endsWith(".htm")) n = n.substring(0, n.length() - 4);
        Path p = root.resolve(n + ".html").normalize();
        if (!p.startsWith(root)) return null;
        return n;
    }

    public boolean exists(String name) {
        String n = normalise(name);
        return n != null && Files.isRegularFile(root.resolve(n + ".html"));
    }

    /** The page if it has finished loading, else null. Never loads, so never waits. */
    public Page loaded(String name) {
        String n = normalise(name);
        CompletableFuture<Loaded> f = n == null ? null : pages.get(n);
        Loaded l = f == null ? null : f.getNow(null);
        return l == null ? null : l.page;
    }

    /**
     * The page, loading it on a loader thread if needed: completes with null if there is no such file
     * or it cannot be read. Any thread; whoever waits for it continues on a loader thread unless they
     * say otherwise.
     */
    public CompletableFuture<Page> load(String name) {
        String n = normalise(name);
        if (n == null) return CompletableFuture.completedFuture(null);
        CompletableFuture<Loaded> f = pages.get(n);
        if (f == null) {
            CompletableFuture<Loaded> mine = new CompletableFuture<>();
            f = pages.putIfAbsent(n, mine);
            if (f == null) {
                f = mine;
                // A failure is not kept: the next ask tries again, as the file may be fixed by then.
                mine.thenAccept(l -> {
                    if (l == null) pages.remove(n, mine);
                });
                try {
                    loaders.execute(() -> {
                        try {
                            Loaded l = read(n);
                            // Not UTF-8: maybe caught mid-write. One more look, as the next check would take.
                            if (l == null && undecodable.containsKey(root.resolve(n + ".html"))) {
                                Thread.sleep(REREAD_MS);
                                l = read(n);
                            }
                            mine.complete(l);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            if (!mine.isDone()) mine.complete(null);
                        }
                    });
                } catch (RejectedExecutionException e) {
                    // The store was closed (a reload or the plugin stopping).
                    mine.complete(null);
                }
            }
        }
        return f.thenApply(l -> l == null ? null : l.page);
    }

    /** Reads page [n] from disk (and the web): slow, never on the server thread. Null if it cannot be. */
    private Loaded read(String n) {
        Path file = root.resolve(n + ".html");
        if (!Files.isRegularFile(file)) return null;
        try {
            Page page = parse(file);
            for (String problem : page.problems) logger.warning("[" + n + "] " + problem);
            return new Loaded(page, stamps(page), false);
        } catch (IOException | RuntimeException | OutOfMemoryError | StackOverflowError e) {
            logger.warning("Could not load page '" + n + "': " + e);
            return null;
        }
    }

    /** Page.load, strict about UTF-8 the first time a file's stamp fails it and lenient the second (see {@link #undecodable}). */
    private Page parse(Path file) throws IOException {
        try {
            Page page = Page.load(file, loader);
            undecodable.remove(file);
            return page;
        } catch (CharacterCodingException e) {
            long stamp = modified(file);
            Long seen = undecodable.put(file, stamp);
            if (seen == null || seen != stamp) throw e;
            undecodable.remove(file);
            return Page.load(file, loader, true);
        }
    }

    private static Map<Path, Long> stamps(Page page) {
        Map<Path, Long> stamps = new ConcurrentHashMap<>();
        for (Path dep : page.dependencies) stamps.put(dep, modified(dep));
        return stamps;
    }

    /** Checks every loaded page's files and reloads those that changed. Off the server thread. */
    public void checkForChanges() {
        // A slow reload (a web fetch) can outlast the timer's period: one check at a time.
        if (!checking.compareAndSet(false, true)) return;
        try {
            for (Map.Entry<String, CompletableFuture<Loaded>> e : pages.entrySet()) {
                CompletableFuture<Loaded> f = e.getValue();
                Loaded old = f.getNow(null);
                if (old == null || (!old.retry && !changed(old))) continue;
                String n = e.getKey();
                Loaded fresh = read(n);
                if (fresh != null) {
                    // Only if nothing (a /ui reload) replaced the entry meanwhile.
                    if (pages.replace(n, f, CompletableFuture.completedFuture(fresh))) {
                        logger.info("Reloaded page '" + n + "'");
                        onReload.accept(n);
                    }
                } else {
                    // Keep showing the old version. An editor may have moved the file aside to save it, or it
                    // was read half-written: try again on the next check, then whenever a file changes again.
                    pages.replace(n, f, CompletableFuture.completedFuture(new Loaded(old.page, stamps(old.page), !old.retry)));
                }
            }
        } finally {
            checking.set(false);
        }
    }

    private static boolean changed(Loaded l) {
        for (Map.Entry<Path, Long> s : l.stamps.entrySet()) if (modified(s.getKey()) != s.getValue()) return true;
        return false;
    }

    public void clear() {
        pages.clear();
    }

    /** Stops the loader threads: those loading finish, nothing new starts. */
    public void close() {
        loaders.shutdown();
    }

    /** Every page in the folder, by name. */
    public List<String> names() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> s = Files.walk(root, 6)) {
            s.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".html")).sorted().forEach(p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                out.add(rel.substring(0, rel.length() - 5));
            });
        } catch (IOException e) {
            // An unreadable folder lists nothing.
        }
        return out;
    }

    /** The page a link on [from] leads to ("shop.html", "../menu.html", "/rules.html"), or null if it is not a page here. */
    public String resolveLink(String from, String href) {
        String target = href;
        int hash = target.indexOf('#');
        if (hash >= 0) target = target.substring(0, hash);
        int query = target.indexOf('?');
        if (query >= 0) target = target.substring(0, query);
        if (target.isBlank()) return from;
        if (target.startsWith("/")) return normalise(target);
        String dir = from.contains("/") ? from.substring(0, from.lastIndexOf('/') + 1) : "";
        Path p = root.resolve(dir + target).normalize();
        if (!p.startsWith(root)) return null;
        String rel = root.relativize(p).toString().replace('\\', '/');
        return normalise(rel);
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }
}
