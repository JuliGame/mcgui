package ai.resourcepack.ui;

import ai.resourcepack.ui.html.Page;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The pages folder: every .html in it (subfolders too) is a page, named by its path without the
 * extension ("menu", "shop/ranks"). Pages load on first use and load again whenever a file they
 * read changes, so editing the HTML (or its CSS, or an image) shows up in game in a second.
 */
public final class PageStore {
    private final Path root;
    private final Page.Loader loader;
    private final Logger logger;
    private final Map<String, Loaded> pages = new ConcurrentHashMap<>();
    /** Told the name of every page that reloads. */
    public volatile Consumer<String> onReload = name -> {};

    private record Loaded(Page page, Map<Path, Long> stamps) {}

    public PageStore(Path root, Page.Loader loader, Logger logger) {
        this.root = root;
        this.loader = loader;
        this.logger = logger;
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

    /** The page, loading it if needed; null if there is no such file or it cannot be read. */
    public Page get(String name) {
        String n = normalise(name);
        if (n == null) return null;
        Loaded l = pages.get(n);
        if (l != null) return l.page;
        return load(n);
    }

    private synchronized Page load(String n) {
        Loaded existing = pages.get(n);
        if (existing != null) return existing.page;
        Path file = root.resolve(n + ".html");
        if (!Files.isRegularFile(file)) return null;
        try {
            Page page = Page.load(file, loader);
            Map<Path, Long> stamps = new ConcurrentHashMap<>();
            for (Path dep : page.dependencies) stamps.put(dep, modified(dep));
            pages.put(n, new Loaded(page, stamps));
            for (String problem : page.problems) logger.warning("[" + n + "] " + problem);
            return page;
        } catch (IOException | RuntimeException e) {
            logger.warning("Could not load page '" + n + "': " + e);
            return null;
        }
    }

    /** Checks every loaded page's files and reloads those that changed. Any thread. */
    public void checkForChanges() {
        for (Map.Entry<String, Loaded> e : pages.entrySet()) {
            boolean changed = false;
            for (Map.Entry<Path, Long> s : e.getValue().stamps.entrySet()) {
                if (modified(s.getKey()) != s.getValue()) {
                    changed = true;
                    break;
                }
            }
            if (!changed) continue;
            pages.remove(e.getKey());
            if (load(e.getKey()) != null) {
                logger.info("Reloaded page '" + e.getKey() + "'");
                onReload.accept(e.getKey());
            }
        }
    }

    public void clear() {
        pages.clear();
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
