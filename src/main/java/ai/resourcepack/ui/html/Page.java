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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
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
        /** The most one web fetch reads, and how long it may take from asking to the last byte, redirects included. */
        private static final int MAX_BYTES = 16 * 1024 * 1024;
        private static final long DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(15);
        /** How long a URL that could not be fetched is left alone, so a dead host does not cost every load its timeout. */
        private static final long RETRY_AFTER_MILLIS = TimeUnit.MINUTES.toMillis(5);

        private final boolean remote, privateHosts;
        private final Path cache, root;
        private final HttpClient http;
        private final Map<String, Long> failed = new ConcurrentHashMap<>();

        /** [remote]: fetch http(s) stylesheets, fonts and images, kept in [cache]. Files are read from anywhere. */
        public Loader(boolean remote, Path cache) {
            this(remote, cache, false, null);
        }

        /**
         * [remote]: fetch http(s) stylesheets, fonts and images, kept in [cache]; [privateHosts]: from
         * loopback, link-local and private network addresses too; [root]: read files only inside it.
         */
        public Loader(boolean remote, Path cache, boolean privateHosts, Path root) {
            this.remote = remote;
            this.privateHosts = privateHosts;
            this.cache = cache;
            this.root = root == null ? null : root.toAbsolutePath().normalize();
            // Redirects are followed by hand, so that every hop's address is checked.
            this.http = remote ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build() : null;
        }

        /** The bytes at [uri], or null. [userAgent] matters for Google Fonts, which picks a format by it. */
        byte[] fetch(URI uri, String userAgent) {
            try {
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                switch (scheme) {
                    case "file" -> {
                        Path p = file(uri);
                        if (p == null) return null;
                        p = p.toAbsolutePath().normalize();
                        // A stylesheet from the web can name file: URLs as well; pages may only read their own folder.
                        if (root != null && !p.startsWith(root)) return null;
                        return Files.isRegularFile(p) && Files.size(p) < 32 * 1024 * 1024 ? Files.readAllBytes(p) : null;
                    }
                    case "data" -> {
                        return dataUri(uri.toString());
                    }
                    case "http", "https" -> {
                        if (!remote) return null;
                        String key = uri + "|" + userAgent;
                        Path cached = cache == null ? null : cache.resolve(hash(key));
                        if (cached != null && Files.isRegularFile(cached)) return Files.readAllBytes(cached);
                        Long retry = failed.get(key);
                        if (retry != null && retry > System.currentTimeMillis()) return null;
                        byte[] body;
                        try {
                            body = download(uri, userAgent);
                        } catch (IOException | RuntimeException e) {
                            body = null;
                        }
                        if (body == null) {
                            remember(key);
                            return null;
                        }
                        if (cached != null) store(cached, body);
                        return body;
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

        /** The body of a 200 answer from [uri], following up to five redirects; null for anything else. */
        private byte[] download(URI uri, String userAgent) throws IOException, InterruptedException {
            long deadline = System.nanoTime() + DEADLINE_NANOS;
            URI at = uri;
            for (int hop = 0; hop <= 5; hop++) {
                String scheme = at.getScheme() == null ? "" : at.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https")) return null;
                if (!privateHosts && !publicHost(at.getHost(), deadline)) return null;
                HttpRequest req = HttpRequest.newBuilder(at).header("User-Agent", userAgent == null ? "Mozilla/5.0 (MC GUI)" : userAgent).build();
                CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(req,
                        info -> info.statusCode() == 200 ? new Capped(MAX_BYTES) : HttpResponse.BodySubscribers.replacing(null));
                HttpResponse<byte[]> res;
                try {
                    // One deadline for the whole answer: a request timeout alone stops counting once the headers are in.
                    res = pending.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (TimeoutException | ExecutionException e) {
                    pending.cancel(true);
                    return null;
                } catch (InterruptedException e) {
                    pending.cancel(true);
                    throw e;
                }
                int status = res.statusCode();
                if (status == 200) return res.body();
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308) return null;
                String location = res.headers().firstValue("Location").orElse(null);
                if (location == null) return null;
                URI next = at.resolve(location.trim().replace(" ", "%20"));
                // From https to http is a downgrade, which browsers (and the client's own redirects) refuse.
                if (scheme.equals("https") && "http".equalsIgnoreCase(next.getScheme())) return null;
                at = next;
            }
            return null;
        }

        /** Name lookups, so a slow DNS server counts against a fetch's deadline. Threads go when idle. */
        private static final ExecutorService LOOKUPS = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "MCGUI-lookup");
            t.setDaemon(true);
            return t;
        });

        /**
         * Whether every address [host] has is out on the internet: not this machine, its network, or a
         * cloud's metadata service. False too when the name cannot be looked up by [deadline].
         */
        private static boolean publicHost(String host, long deadline) throws InterruptedException {
            if (host == null || host.isEmpty()) return false;
            Future<InetAddress[]> lookup = LOOKUPS.submit(() -> InetAddress.getAllByName(host));
            InetAddress[] all;
            try {
                all = lookup.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException | ExecutionException e) {
                lookup.cancel(true);
                return false;
            }
            for (InetAddress a : all) if (!publicAddress(a)) return false;
            return true;
        }

        static boolean publicAddress(InetAddress a) {
            if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
            byte[] b = a.getAddress();
            if (b.length == 16) {
                // An IPv4 address inside an IPv6 one (::ffff:a.b.c.d, ::a.b.c.d, NAT64's 64:ff9b::a.b.c.d) reaches
                // that IPv4 address. Java unwraps only literals, not what DNS returns, so it is judged here.
                if (embedsIpv4(b)) {
                    try {
                        return publicAddress(InetAddress.getByAddress(Arrays.copyOfRange(b, 12, 16)));
                    } catch (UnknownHostException e) {
                        return false;
                    }
                }
                // IPv6 unique local addresses (fc00::/7), the IPv6 private networks.
                return (b[0] & 0xFE) != 0xFC;
            }
            int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
            // 0.0.0.0/8, carrier-grade NAT 100.64.0.0/10, and the reserved 240.0.0.0/4.
            return !(b0 == 0 || (b0 == 100 && (b1 & 0xC0) == 64) || b0 >= 240);
        }

        private static boolean embedsIpv4(byte[] b) {
            boolean nat64 = b[0] == 0 && b[1] == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B;
            for (int i = nat64 ? 4 : 0; i < 10; i++) if (b[i] != 0) return false;
            int b10 = b[10] & 0xFF, b11 = b[11] & 0xFF;
            return nat64 ? b10 == 0 && b11 == 0 : (b10 == 0xFF && b11 == 0xFF) || (b10 == 0 && b11 == 0);
        }

        private void remember(String key) {
            long now = System.currentTimeMillis();
            if (failed.size() > 1000) failed.values().removeIf(t -> t < now);
            failed.put(key, now + RETRY_AFTER_MILLIS);
        }

        /** Keeps [body] in [cached], written beside it first and moved into place whole: a cut-off file would be served from then on. */
        private static void store(Path cached, byte[] body) {
            try {
                Files.createDirectories(cached.getParent());
                Path part = Files.createTempFile(cached.getParent(), cached.getFileName().toString(), ".part");
                try {
                    Files.write(part, body);
                    Files.move(part, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(part);
                }
            } catch (IOException e) {
                // Not kept: it is fetched again next time.
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

    /** A response body read into memory, given up as soon as it passes [max] bytes. */
    private static final class Capped implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final int max;
        private Flow.Subscription subscription;

        Capped(int max) {
            this.max = max;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) return;
            for (ByteBuffer b : items) {
                if (out.size() + b.remaining() > max) {
                    subscription.cancel();
                    result.completeExceptionally(new IOException("more than " + max + " bytes"));
                    return;
                }
                byte[] chunk = new byte[b.remaining()];
                b.get(chunk);
                out.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable t) {
            result.completeExceptionally(t);
        }

        @Override
        public void onComplete() {
            result.complete(out.toByteArray());
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
    /**
     * Pictures from somewhere other than files and the web, by URI scheme ({@code head:} for a player's
     * face): looked up as the page is drawn, since what they name is often filled in per player. Each
     * answers null for one it does not have yet. Set by whoever runs the renderer; none by default.
     */
    public static final Map<String, Function<String, Picture>> SCHEMES = new ConcurrentHashMap<>();
    /** Whether the page names any picture of the {@link #SCHEMES}: what it draws can change after it is loaded. */
    private boolean schemed;
    /*
     * Pictures made while drawing: data: images found only then, and inline SVGs. Bounded, because
     * their keys can hold placeholder values (a player's name, a number that changes every refresh).
     */
    private final Map<String, Picture> dataImages = lru(256), svgs = lru(512);
    /** See {@link #siblingStates()}. */
    private boolean siblingStates;
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
                noteSelectors(e.data());
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
                noteSelectors(css);
                CssParser.parse(css, target, u -> importSheet(u, loader), styles);
            }
        }
        this.fonts = Fonts.bundled().withPageFonts(fontFaces(loader));
        this.shaper = new TextShaper(fonts);
        loadImages(loader);
    }

    /**
     * Loads [file] and everything it uses. Bytes that are not UTF-8 fail the load (a MalformedInputException)
     * rather than reading as U+FFFD: PageStore takes that as a file caught mid-write and reads it again,
     * and only when it still is not, with its stamp unchanged, loads it [lenient]ly.
     */
    public static Page load(Path file, Loader loader) throws IOException {
        return load(file, loader, false);
    }

    /** {@link #load(Path, Loader)}, reading bytes that are not UTF-8 as U+FFFD when [lenient] (and noting it), as browsers do. */
    public static Page load(Path file, Loader loader, boolean lenient) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (!lenient) return new Page(file, decode(bytes, false), loader);
        Page page = new Page(file, decode(bytes, true), loader);
        page.problems.add(0, "Not UTF-8: read with U+FFFD in place of the bytes that are not");
        return page;
    }

    /** A page's text: UTF-8 unless a byte order mark says UTF-16, as browsers sniff it; strictly UTF-8 unless [lenient]. */
    static String decode(byte[] b, boolean lenient) throws IOException {
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        int skip = b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF ? 3 : 0;
        if (lenient) return new String(b, skip, b.length - skip, StandardCharsets.UTF_8);
        return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b, skip, b.length - skip)).toString();
    }

    /** A page from a string (tests, built-in pages), resolving relative files against [base]. */
    public static Page of(String html, Path base, Loader loader) {
        return new Page(base, html, loader);
    }

    private String importSheet(URI u, Loader loader) {
        String css = sheet(u, loader);
        noteSelectors(css);
        return css;
    }

    /**
     * Whether a :hover or :active selector may also pick elements beside the hovered one, through a
     * '+' or '~' combinator: then a change of hover can repaint things outside the hovered element.
     * Read off the stylesheets' text, so it errs towards yes.
     */
    public boolean siblingStates() {
        return siblingStates;
    }

    /**
     * Notes whether [css] has a :hover or :active selector with a '+' or '~' combinator. Linear scans
     * only: this runs over remote stylesheets, where a regex with backtracking could be made to take hours.
     */
    private void noteSelectors(String css) {
        if (siblingStates || css == null) return;
        String s = withoutComments(css);
        // Every selector is what comes before a '{' since the last ';', '}' or '{'.
        int from = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ';' || c == '}') {
                from = i + 1;
            } else if (c == '{') {
                String prelude = s.substring(from, i).toLowerCase(Locale.ROOT);
                from = i + 1;
                if ((prelude.contains(":hover") || prelude.contains(":active")) && siblingCombinator(prelude)) {
                    siblingStates = true;
                    return;
                }
            }
        }
    }

    private static String withoutComments(String css) {
        int open = css.indexOf("/*");
        if (open < 0) return css;
        StringBuilder out = new StringBuilder(css.length());
        int from = 0;
        while (open >= 0) {
            out.append(css, from, open);
            int close = css.indexOf("*/", open + 2);
            // An unclosed comment runs to the end, as in CSS.
            if (close < 0) return out.toString();
            from = close + 2;
            open = css.indexOf("/*", from);
        }
        return out.append(css, from, css.length()).toString();
    }

    /**
     * Whether [selector] has a '+' or '~' combinator. Those inside an attribute selector ([class~=x]) or
     * an an+b argument (2n+1) are not combinators; those inside :is() and the like are.
     */
    private static boolean siblingCombinator(String selector) {
        int n = selector.length();
        for (int i = 0; i < n; i++) {
            char c = selector.charAt(i);
            if (c == '[') {
                int close = selector.indexOf(']', i + 1);
                if (close < 0) return false;
                i = close;
            } else if (c == '(') {
                int j = i + 1;
                while (j < n && "-+0123456789n \t\n\r\f".indexOf(selector.charAt(j)) >= 0) j++;
                if (j < n && selector.charAt(j) == ')') i = j;
            } else if (c == '+' || c == '~') {
                return true;
            }
        }
        return false;
    }

    private String sheet(URI target, Loader loader) {
        depend(target);
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
                depend(u);
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

    /**
     * Decoded images of one page take at most this many bytes. Past it, the rest are not loaded (and not
     * drawn): a page of a thousand distinct photos would otherwise hold gigabytes for as long as it is open.
     */
    static final long MAX_IMAGE_BYTES = 256L << 20;

    /** Every image the page could draw, fetched now, up to {@link #MAX_IMAGE_BYTES} decoded. */
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
        long used = 0;
        int over = 0;
        for (String u : wanted) {
            if (images.containsKey(u)) continue;
            if (scheme(u) != null) {
                schemed = true;
                continue;
            }
            URI target = resolve(null, u);
            if (target == null) continue;
            // url(#id) names something in the page itself (an SVG filter, clip or gradient), not an image file.
            if (target.getFragment() != null && file(target) != null && file(target).equals(file(uri))) continue;
            depend(target);
            byte[] data = loader.fetch(target, null);
            // Weighed from the header before decoding, so the bytes past the budget are never allocated at all.
            long size = Pictures.decodedBytes(data, u);
            if (size > 0 && used + size > MAX_IMAGE_BYTES) {
                over++;
                // Kept as a failure, so drawing does not decode a data: one after all.
                images.put(u, NONE_PICTURE);
                continue;
            }
            Picture p = Pictures.decode(data, u);
            if (p != null) {
                images.put(u, p);
                used += size;
            } else if (!u.startsWith("data:")) {
                problems.add("Could not load image " + u);
            }
        }
        if (over > 0) problems.add(over + " images not loaded: the page's images would take over " + (MAX_IMAGE_BYTES >> 20) + " MB decoded");
    }

    /** Notes a file: [uri] as one this page read, so that changing it loads the page again. */
    private void depend(URI uri) {
        if (!"file".equalsIgnoreCase(uri.getScheme())) return;
        Path p = file(uri);
        if (p != null) dependencies.add(p);
    }

    /**
     * The file a file: URI names, without its #fragment or ?query (Path.of refuses a URI with either,
     * and {@code url(icons.svg#home)} names the file icons.svg), or null if it names no local file.
     */
    static Path file(URI uri) {
        try {
            return uri.getPath() == null || uri.getAuthority() != null ? null : Path.of(new URI("file", null, uri.getPath(), null));
        } catch (URISyntaxException | RuntimeException e) {
            return null;
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

    /** The bytes of a data: URI, or null when it has no comma or its base64 is broken. */
    static byte[] dataUri(String s) {
        int comma = s.indexOf(',');
        if (comma < 0) return null;
        String meta = s.substring(5, comma).toLowerCase(Locale.ROOT);
        byte[] body = percentDecode(s.substring(comma + 1));
        if (!meta.endsWith(";base64")) return body;
        try {
            // The MIME decoder skips what is not base64, such as the spaces of a wrapped line.
            return Base64.getMimeDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * [s] with its percent escapes decoded, leniently: a '%' not followed by two hex digits stays as it
     * is, and so does a '+'. URLDecoder throws on the first (an SVG's width="100%") and turns the second
     * into a space.
     */
    static byte[] percentDecode(String s) {
        if (s.indexOf('%') < 0) return s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        int start = 0;
        for (int i = 0; i + 2 < s.length(); i++) {
            if (s.charAt(i) != '%') continue;
            int hi = hex(s.charAt(i + 1)), lo = hex(s.charAt(i + 2));
            if (hi < 0 || lo < 0) continue;
            out.writeBytes(s.substring(start, i).getBytes(StandardCharsets.UTF_8));
            out.write(hi << 4 | lo);
            i += 2;
            start = i + 1;
        }
        out.writeBytes(s.substring(start).getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    // ---------------------------------------------------------------- Resources

    /** The {@link #SCHEMES} entry for [uri], or null. */
    private static Function<String, Picture> scheme(String uri) {
        int colon = uri.indexOf(':');
        return colon > 0 ? SCHEMES.get(uri.substring(0, colon).toLowerCase(java.util.Locale.ROOT)) : null;
    }

    private Boolean moves;

    /**
     * Whether anything on the page can animate or transition ({@code animation}, {@code transition}): its
     * pictures are then drawn with a motion, at the moment each is of, again and again while something moves.
     */
    public boolean moves() {
        Boolean m = moves;
        if (m == null) {
            m = !styles.keyframes.isEmpty();
            for (Stylesheet.Rule r : styles.rules()) {
                if (m) break;
                for (Declaration d : r.declarations()) if (d.property().startsWith("transition") || d.property().startsWith("animation")) m = true;
            }
            if (!m) m = !document.select("[style*=transition], [style*=animation]").isEmpty();
            moves = m;
        }
        return m;
    }

    /** Whether the page draws pictures of the {@link #SCHEMES}, which can arrive (and change) after it has loaded. */
    public boolean schemed() {
        return schemed;
    }

    @Override
    public Picture image(String uri) {
        Function<String, Picture> scheme = scheme(uri);
        if (scheme != null) {
            try {
                return scheme.apply(uri);
            } catch (RuntimeException e) {
                return null;
            }
        }
        Picture p = images.get(uri);
        if (p == null && uri.startsWith("data:")) p = made(dataImages, uri, u -> Pictures.decode(dataUri(u), u));
        return p == NONE_PICTURE ? null : p;
    }

    @Override
    public Picture svg(String markup) {
        Picture p = made(svgs, markup, Pictures::svg);
        return p == NONE_PICTURE ? null : p;
    }

    /**
     * [cache]'s picture for [key], made with [make] if it has none. A failure is kept too (as
     * NONE_PICTURE), so a broken picture costs one attempt, not one every frame.
     */
    private static Picture made(Map<String, Picture> cache, String key, Function<String, Picture> make) {
        Picture p = cache.get(key);
        if (p != null) return p;
        // Made outside the map's lock, so render threads do not wait on each other's parsing; two
        // making the same picture at once cost only time.
        try {
            p = make.apply(key);
        } catch (RuntimeException e) {
            p = null;
        }
        if (p == null) p = NONE_PICTURE;
        cache.put(key, p);
        return p;
    }

    /** A map that forgets its least recently used entries beyond [max]; safe from any thread. */
    private static Map<String, Picture> lru(int max) {
        return Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Picture> eldest) {
                return size() > max;
            }
        });
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

    /** Whether the page says it was made on mcgui.dev, {@code <meta name="generator" content="mcgui.dev">}, as its exports do. */
    public boolean madeOnBuilder() {
        String g = meta("generator");
        return g != null && g.toLowerCase(java.util.Locale.ROOT).contains("mcgui.dev");
    }

    /** What {@code <meta name="ui:overlay">} says (see {@link #overlay()}); [on] false for a page without it. */
    private record Overlay(boolean on, int tint, boolean blur) {}

    private static final Overlay NOT_OVERLAY = new Overlay(false, 0, false);
    /** Read the first time it is asked for. */
    private volatile Overlay overlay;

    /**
     * Whether this is an overlay page, {@code <meta name="ui:overlay" content="...">}: drawn on nothing rather than
     * on white, so wherever it paints nothing (a transparent html and body) its picture is transparent, and a
     * fullscreen view shows the world behind the player's window there, under {@link #overlayTint()}.
     */
    public boolean overlay() {
        return overlayMeta().on();
    }

    /**
     * What an overlay page's view lays over the world where the page is transparent: the meta's content, a CSS
     * colour (ARGB; {@code rgba(10, 8, 24, 0.5)}, say), or 0 when it is empty, {@code none} or not a colour. 0
     * for a page that is not an overlay.
     */
    public int overlayTint() {
        return overlayMeta().tint();
    }

    /**
     * Whether an overlay page's view also blurs the world round its panel: the word {@code blur} in the meta's
     * content, before or after the colour ({@code content="rgba(10, 8, 24, 0.35) blur"}).
     */
    public boolean overlayBlur() {
        return overlayMeta().blur();
    }

    private Overlay overlayMeta() {
        Overlay o = overlay;
        if (o != null) return o;
        String content = meta("ui:overlay");
        if (content == null) o = NOT_OVERLAY;
        else {
            String c = content.trim();
            boolean blur = c.matches("(?i).*\\bblur\\b.*");
            c = c.replaceAll("(?i)\\bblur\\b", "").trim();
            Integer argb = c.isEmpty() || c.equalsIgnoreCase("none") ? null : ai.resourcepack.ui.html.css.Values.color(c, 0xFF000000);
            o = new Overlay(true, argb == null ? 0 : argb, blur);
        }
        overlay = o;
        return o;
    }

    public String title() {
        String t = document.title();
        return t.isBlank() ? file.getFileName().toString() : t;
    }
}
