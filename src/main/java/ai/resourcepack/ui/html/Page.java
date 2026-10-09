package ai.resourcepack.ui.html;

import ai.resourcepack.ui.html.css.CssParser;
import ai.resourcepack.ui.html.css.Declaration;
import ai.resourcepack.ui.html.css.FontFace;
import ai.resourcepack.ui.html.css.Stylesheet;
import ai.resourcepack.ui.html.res.Picture;
import ai.resourcepack.ui.html.res.Pictures;
import ai.resourcepack.ui.html.res.Resources;
import ai.resourcepack.ui.html.text.Fonts;
import ai.resourcepack.ui.html.text.TextShaper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An HTML page and everything it uses, loaded up front: its stylesheets ({@code <style>},
 * {@code <link rel=stylesheet>}, {@code @import}), its {@code @font-face} fonts (Google Fonts
 * included), and its images. Rendering never touches the disk or the network: a page is loaded once
 * and drawn many times.
 */
public final class Page implements Resources {
    /** How a page reaches files: its own folder always, the web if allowed. */
    public static final class Loader {
        private final boolean remote;
        private final Path cache;
        private final HttpClient http;

        /** [remote]: fetch http(s) stylesheets, fonts and images, kept in [cache]. */
        public Loader(boolean remote, Path cache) {
            this.remote = remote;
            this.cache = cache;
            this.http = remote ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NORMAL).build() : null;
        }

        /** The bytes at [uri], or null. [userAgent] matters for Google Fonts, which picks a format by it. */
        byte[] fetch(URI uri, String userAgent) {
            try {
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                switch (scheme) {
                    case "file" -> {
                        Path p = Path.of(uri);
                        return Files.isRegularFile(p) && Files.size(p) < 32 * 1024 * 1024 ? Files.readAllBytes(p) : null;
                    }
                    case "data" -> {
                        return dataUri(uri.toString());
                    }
                    case "http", "https" -> {
                        if (!remote) return null;
                        Path cached = cache == null ? null : cache.resolve(hash(uri + "|" + userAgent));
                        if (cached != null && Files.isRegularFile(cached)) return Files.readAllBytes(cached);
                        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
                        req.header("User-Agent", userAgent == null ? "Mozilla/5.0 (UI Engine)" : userAgent);
                        HttpResponse<byte[]> res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
                        if (res.statusCode() != 200 || res.body().length > 16 * 1024 * 1024) return null;
                        if (cached != null) {
                            Files.createDirectories(cached.getParent());
                            Files.write(cached, res.body());
                        }
                        return res.body();
                    }
                    default -> {
                        return null;
                    }
                }
            } catch (IOException | RuntimeException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        private static String hash(String s) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
            } catch (Exception e) {
                return Integer.toHexString(s.hashCode());
            }
        }
    }

    /** The browser's defaults, parsed once. */
    public static final Stylesheet UA = new Stylesheet();

    static {
        CssParser.parse(ai.resourcepack.ui.html.style.Styler.UA_CSS, null, u -> null, UA);
    }

    public final Path file;
    public final URI uri;
    public final Document document;
    public final Stylesheet styles = new Stylesheet();
    public final Fonts fonts;
    public final TextShaper shaper;
    private final Map<String, Picture> images = new ConcurrentHashMap<>();
    private final Map<String, Picture> svgs = new ConcurrentHashMap<>();
    /** Files this page read: when any changes, it is loaded again. */
    public final Set<Path> dependencies = new LinkedHashSet<>();
    public final List<String> problems = new ArrayList<>();

    private Page(Path file, String html, Loader loader) {
        this.file = file;
        this.uri = file.toUri();
        this.document = Jsoup.parse(html, uri.toString());
        dependencies.add(file);
        // Stylesheets in document order.
        for (Element e : document.select("style, link[rel~=(?i)stylesheet]")) {
            if (e.normalName().equals("style")) {
                CssParser.parse(e.data(), uri, u -> importSheet(u, loader), styles);
            } else {
                String href = e.attr("href");
                if (href.isBlank()) continue;
                URI target = resolve(uri, href);
                if (target == null) continue;
                String css = sheet(target, loader);
                if (css == null) {
                    problems.add("Could not load stylesheet " + href);
                    continue;
                }
                CssParser.parse(css, target, u -> importSheet(u, loader), styles);
            }
        }
        this.fonts = Fonts.bundled().withPageFonts(fontFaces(loader));
        this.shaper = new TextShaper(fonts);
        loadImages(loader);
    }

    /** Loads [file] and everything it uses. */
    public static Page load(Path file, Loader loader) throws IOException {
        return new Page(file, Files.readString(file, StandardCharsets.UTF_8), loader);
    }

    /** A page from a string (tests, built-in pages), resolving relative files against [base]. */
    public static Page of(String html, Path base, Loader loader) {
        return new Page(base, html, loader);
    }

    private String importSheet(URI u, Loader loader) {
        return sheet(u, loader);
    }

    private String sheet(URI target, Loader loader) {
        if ("file".equalsIgnoreCase(target.getScheme())) dependencies.add(Path.of(target));
        // Google Fonts sends TrueType to a browser it does not recognise; that is what Java can read.
        byte[] data = loader.fetch(target, isGoogleFonts(target) ? "Mozilla/5.0" : null);
        return data == null ? null : new String(data, StandardCharsets.UTF_8);
    }

    private static boolean isGoogleFonts(URI u) {
        return u.getHost() != null && u.getHost().endsWith("fonts.googleapis.com");
    }

    private List<Fonts.PageFont> fontFaces(Loader loader) {
        List<Fonts.PageFont> out = new ArrayList<>();
        for (FontFace face : styles.fontFaces) {
            for (String src : face.sources()) {
                String lower = src.toLowerCase(Locale.ROOT);
                // Java reads TrueType and OpenType, not WOFF.
                if (lower.contains(".woff")) continue;
                URI u = resolve(uri, src);
                if (u == null) continue;
                if ("file".equalsIgnoreCase(u.getScheme())) dependencies.add(Path.of(u));
                byte[] data = loader.fetch(u, null);
                if (data != null) {
                    out.add(new Fonts.PageFont(face.family(), face.weight(), face.italic(), data));
                    break;
                }
            }
        }
        return out;
    }

    private static final Pattern URL = Pattern.compile("url\\(\\s*['\"]?([^'\")]+)['\"]?\\s*\\)", Pattern.CASE_INSENSITIVE);

    /** Every image the page could draw, fetched now. */
    private void loadImages(Loader loader) {
        Set<String> wanted = new LinkedHashSet<>();
        for (Element img : document.select("img[src], img[srcset], video[poster], input[type=image][src], object[data], embed[src]")) {
            for (String attr : new String[]{"src", "poster", "data"}) {
                if (img.hasAttr(attr) && !img.attr(attr).isBlank()) wanted.add(CssParser.resolve(uri, img.attr(attr)));
            }
            if (img.hasAttr("srcset") && !img.hasAttr("src")) wanted.add(CssParser.resolve(uri, img.attr("srcset").trim().split("[\\s,]")[0]));
        }
        for (Stylesheet.Rule r : styles.rules()) for (Declaration d : r.declarations()) urls(d.value(), wanted);
        for (Element e : document.select("[style]")) {
            for (Declaration d : CssParser.declarations(e.attr("style"), uri)) urls(d.value(), wanted);
        }
        for (String u : wanted) {
            if (images.containsKey(u)) continue;
            URI target = resolve(null, u);
            if (target == null) continue;
            if ("file".equalsIgnoreCase(target.getScheme())) dependencies.add(Path.of(target));
            Picture p = Pictures.decode(loader.fetch(target, null), u);
            if (p != null) images.put(u, p);
            else if (!u.startsWith("data:")) problems.add("Could not load image " + u);
        }
    }

    private static void urls(String value, Set<String> out) {
        if (!value.contains("url(")) return;
        Matcher m = URL.matcher(value);
        while (m.find()) out.add(m.group(1).trim());
    }

    static URI resolve(URI base, String target) {
        try {
            if (target.startsWith("data:")) return URI.create(target.replace(" ", "%20"));
            URI t = URI.create(target.trim().replace(" ", "%20"));
            return base == null || t.isAbsolute() ? t : base.resolve(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] dataUri(String s) {
        int comma = s.indexOf(',');
        if (comma < 0) return null;
        String meta = s.substring(5, comma);
        String body = s.substring(comma + 1);
        if (meta.endsWith(";base64")) return Base64.getMimeDecoder().decode(body.replace("%20", ""));
        return java.net.URLDecoder.decode(body, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- Resources

    @Override
    public Picture image(String uri) {
        Picture p = images.get(uri);
        if (p == null && uri.startsWith("data:")) {
            p = Pictures.decode(dataUri(uri), uri);
            if (p != null) images.put(uri, p);
        }
        return p;
    }

    @Override
    public Picture svg(String markup) {
        Picture p = svgs.computeIfAbsent(markup, m -> {
            Picture decoded = Pictures.svg(m);
            return decoded == null ? NONE_PICTURE : decoded;
        });
        return p == NONE_PICTURE ? null : p;
    }

    private static final Picture NONE_PICTURE = new Picture() {
        @Override public double width() { return 0; }
        @Override public double height() { return 0; }
        @Override public void draw(java.awt.Graphics2D g, double x, double y, double w, double h) {}
    };

    /** A {@code <meta name="...">}'s content, or null. */
    public String meta(String name) {
        Element m = document.selectFirst("meta[name=" + name + "]");
        return m == null ? null : m.attr("content");
    }

    public String title() {
        String t = document.title();
        return t.isBlank() ? file.getFileName().toString() : t;
    }
}
