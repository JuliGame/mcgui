package ai.resourcepack.ui.html.css;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads a stylesheet: qualified rules, {@code @media}, {@code @supports}, {@code @layer},
 * {@code @font-face} and {@code @import}. Everything else ({@code @keyframes}, {@code @page}) is
 * skipped whole. Forgiving the way browsers are: a rule it cannot read is dropped and the rest kept.
 *
 * <p>Every {@code url(...)} in a value is resolved against the sheet's own address as it is read,
 * because a relative url in an imported stylesheet is relative to that file, not to the page.
 */
public final class CssParser {
    private final String src;
    private final URI base;
    private final Imports imports;
    private int pos;
    private final Stylesheet out;
    private int depth;

    /** What every sheet of one parse shares about @import: the sheets being read, how many, and their text. */
    private static final class Imports {
        /** Most sheets one parse imports: enough for any page, few enough that a server cannot fan out forever. */
        static final int MAX = 64;
        final Function<URI, String> importer;
        /** The sheets on the current import chain: one importing any of them again is a cycle. */
        final Set<URI> chain = new HashSet<>();
        /** Each sheet's text, fetched once however often it is imported (null for one that failed). */
        final Map<URI, String> fetched = new HashMap<>();
        int count;

        Imports(Function<URI, String> importer) {
            this.importer = importer;
        }
    }

    private CssParser(String src, URI base, Stylesheet out, Imports imports) {
        this.src = stripComments(src);
        this.base = base;
        this.out = out;
        this.imports = imports;
    }

    /**
     * Parses [css] into [into]. [importer] fetches an {@code @import}ed sheet's text, or returns null.
     * [origin] orders rules across sheets: later sheets win ties.
     */
    public static void parse(String css, URI base, Function<URI, String> importer, Stylesheet into) {
        Imports imports = new Imports(importer);
        if (base != null) imports.chain.add(base.normalize());
        new CssParser(css, base, into, imports).sheet(null);
    }

    /** A {@code style=""} attribute's declarations. */
    public static List<Declaration> declarations(String block, URI base) {
        CssParser p = new CssParser(block, base, new Stylesheet(), new Imports(u -> null));
        return p.declarationList(p.src);
    }

    private static String stripComments(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        char quote = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (quote != 0) {
                b.append(c);
                if (c == '\\' && i + 1 < s.length()) b.append(s.charAt(++i));
                else if (c == quote) quote = 0;
                i++;
            } else if (c == '"' || c == '\'') {
                quote = c;
                b.append(c);
                i++;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? s.length() : end + 2;
                b.append(' ');
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }

    private void sheet(Media media) {
        while (true) {
            skipSpace();
            if (pos >= src.length()) return;
            char c = src.charAt(pos);
            if (c == '}') {
                pos++;
                return;
            }
            if (c == '<' && src.startsWith("<!--", pos)) {
                pos += 4;
                continue;
            }
            if (c == '-' && src.startsWith("-->", pos)) {
                pos += 3;
                continue;
            }
            if (c == '@') atRule(media);
            else qualifiedRule(media);
        }
    }

    private void atRule(Media media) {
        int start = ++pos;
        while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '-')) pos++;
        String name = src.substring(start, pos).toLowerCase(Locale.ROOT);
        int preludeStart = pos;
        // The prelude runs to a ';' or a '{' at the top level.
        int end = scanTo(pos, ";{");
        String prelude = src.substring(preludeStart, Math.min(end, src.length())).trim();
        if (end >= src.length()) {
            pos = src.length();
            return;
        }
        boolean block = src.charAt(end) == '{';
        pos = end + 1;
        switch (name) {
            case "media" -> {
                if (!block) return;
                depth++;
                if (depth < 20) sheet(Media.and(media, Media.parse(prelude)));
                else skipBlock();
                depth--;
            }
            case "supports", "layer", "container", "document", "scope" -> {
                if (!block) return;
                depth++;
                if (depth < 20) sheet(media);
                else skipBlock();
                depth--;
            }
            case "font-face" -> {
                if (!block) return;
                int close = matching(pos - 1);
                List<Declaration> decls = declarationList(src.substring(pos, close));
                pos = close + 1;
                FontFace face = FontFace.from(decls);
                if (face != null) out.fontFaces.add(face);
            }
            case "import" -> {
                if (block) {
                    skipBlock();
                    return;
                }
                importSheet(prelude, media);
            }
            default -> {
                if (block) skipBlock();
            }
        }
    }

    private void importSheet(String prelude, Media media) {
        String target;
        String rest;
        if (prelude.startsWith("url(")) {
            int close = prelude.indexOf(')');
            if (close < 0) return;
            target = unquote(prelude.substring(4, close).trim());
            rest = prelude.substring(close + 1).trim();
        } else if (!prelude.isEmpty() && (prelude.charAt(0) == '"' || prelude.charAt(0) == '\'')) {
            int close = prelude.indexOf(prelude.charAt(0), 1);
            if (close < 0) return;
            target = prelude.substring(1, close);
            rest = prelude.substring(close + 1).trim();
        } else {
            return;
        }
        URI uri;
        try {
            uri = base == null ? URI.create(target) : base.resolve(target.replace(" ", "%20"));
        } catch (IllegalArgumentException e) {
            return;
        }
        // A sheet importing one of the sheets that led to it is a cycle, skipped as browsers do. The same
        // sheet imported again elsewhere is read again, under that import's media and at its place in
        // the cascade; only the count of imports stops a server fanning out into endless distinct sheets.
        URI key = uri.normalize();
        if (depth > 8 || imports.chain.contains(key) || imports.count >= Imports.MAX) return;
        if (!imports.fetched.containsKey(key)) imports.fetched.put(key, imports.importer.apply(uri));
        String css = imports.fetched.get(key);
        if (css == null) return;
        imports.count++;
        Media m = rest.isEmpty() ? media : Media.and(media, Media.parse(rest));
        CssParser child = new CssParser(css, uri, out, imports);
        child.depth = depth + 1;
        imports.chain.add(key);
        child.sheet(m);
        imports.chain.remove(key);
    }

    private void qualifiedRule(Media media) {
        int open = scanTo(pos, "{;}");
        if (open >= src.length()) {
            pos = src.length();
            return;
        }
        if (src.charAt(open) != '{') {
            // Junk at the top level: skip it.
            pos = open + 1;
            return;
        }
        String prelude = src.substring(pos, open).trim();
        int close = matching(open);
        String body = src.substring(open + 1, Math.min(close, src.length()));
        pos = Math.min(close + 1, src.length());
        List<Selector> selectors = Selector.parseList(prelude);
        if (selectors.isEmpty()) return;
        List<Declaration> decls = declarationList(body);
        if (decls.isEmpty()) return;
        out.add(selectors, decls, media);
    }

    /** Declarations in a block, nested rules (CSS nesting) ignored. */
    private List<Declaration> declarationList(String block) {
        List<Declaration> list = new ArrayList<>();
        int i = 0;
        while (i < block.length()) {
            int end = scan(block, i, ";{");
            if (end < block.length() && block.charAt(end) == '{') {
                // A nested rule: skip its block.
                int depth = 0;
                int j = end;
                for (; j < block.length(); j++) {
                    char c = block.charAt(j);
                    if (c == '{') depth++;
                    else if (c == '}' && --depth == 0) break;
                }
                i = j + 1;
                continue;
            }
            String decl = block.substring(i, Math.min(end, block.length()));
            i = end + 1;
            int colon = decl.indexOf(':');
            if (colon <= 0) continue;
            String prop = decl.substring(0, colon).trim();
            String value = decl.substring(colon + 1).trim();
            if (prop.isEmpty() || value.isEmpty()) continue;
            boolean important = false;
            int bang = value.toLowerCase(Locale.ROOT).lastIndexOf("!important");
            if (bang >= 0 && value.substring(bang + 10).isBlank()) {
                important = true;
                value = value.substring(0, bang).trim();
            }
            if (!prop.startsWith("--")) prop = prop.toLowerCase(Locale.ROOT);
            list.add(new Declaration(prop, resolveUrls(value), important));
        }
        return list;
    }

    /** Rewrites every url(x) in [value] to an absolute one. */
    private String resolveUrls(String value) {
        if (base == null || !value.contains("url(")) return value;
        StringBuilder b = new StringBuilder();
        int i = 0;
        while (true) {
            int at = value.indexOf("url(", i);
            if (at < 0) {
                b.append(value, i, value.length());
                return b.toString();
            }
            int close = value.indexOf(')', at);
            if (close < 0) {
                b.append(value, i, value.length());
                return b.toString();
            }
            String target = unquote(value.substring(at + 4, close).trim());
            b.append(value, i, at).append("url(\"").append(resolve(base, target)).append("\")");
            i = close + 1;
        }
    }

    public static String resolve(URI base, String target) {
        if (target.startsWith("data:") || base == null) return target;
        try {
            return base.resolve(target.replace(" ", "%20")).toString();
        } catch (IllegalArgumentException e) {
            return target;
        }
    }

    static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'') && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private void skipBlock() {
        int close = matching(pos - 1);
        pos = Math.min(close + 1, src.length());
    }

    /** The index of the '}' matching the '{' at [open], or the end. */
    private int matching(int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') quote = c;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i;
        }
        return src.length();
    }

    private int scanTo(int from, String stops) {
        return scan(src, from, stops);
    }

    /** The first of [stops] at [from] or after, outside strings and parentheses. */
    static int scan(String s, int from, String stops) {
        int paren = 0;
        char quote = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') quote = c;
            else if (c == '(' || c == '[') paren++;
            else if ((c == ')' || c == ']') && paren > 0) paren--;
            else if (paren == 0 && stops.indexOf(c) >= 0) return i;
        }
        return s.length();
    }

    private void skipSpace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }
}
