package ai.resourcepack.ui.framework.pack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GLSL compared as code rather than as bytes: whether another plugin's copy of one of our shaders still carries all of
 * ours, merged with its own code: MC GUI's file with its {@code main} renamed and called from a new {@code main} beside
 * the plugin's own, which is how a pack with its own copy of a core shader keeps MC GUI's screens working.
 * <p>
 * A shader is read as its top-level pieces (declarations, functions, directives), each written in one canonical form:
 * comments gone, and whitespace only where it separates two words. Another copy carries ours when it has every piece
 * of ours but {@code main}, after its own imports of files in the pack are read in, and runs our {@code main}'s work
 * from its own: a function with exactly our {@code main}'s body, or, when our {@code main} only calls functions of ours,
 * each of those, reachable from its {@code main}. Extra pieces of its own are its business. What this cannot see is
 * whether the merged file compiles: two declarations of one name, or a varying at a location we use, fail in the game
 * and nowhere here.
 */
final class ShaderCode {
    private ShaderCode() {}

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.]+|\\S");
    private static final Pattern FUNCTION = Pattern.compile("^[A-Za-z0-9_ ]*?\\b(\\w+)\\(([^(){};]*)\\)\\{(.*)}$", Pattern.DOTALL);
    private static final Pattern CALL = Pattern.compile("\\b(\\w+)\\(");
    private static final Pattern BARE_CALL = Pattern.compile("^(\\w+)\\(\\)$");
    private static final Pattern IMPORT = Pattern.compile("^#(?:moj_import|include)<(?:(\\w+):)?([^>]+)>$");
    private static final Pattern BLOCK = Pattern.compile("^layout\\(std140\\)uniform (\\w+)\\{");
    /** Uniform blocks a game include declares: a copy that imports the include has the block, and the other way round. */
    private static final Map<String, String> BLOCK_INCLUDES = Map.of(
            "DynamicTransforms", "minecraft:dynamictransforms.glsl",
            "Projection", "minecraft:projection.glsl",
            "Globals", "minecraft:globals.glsl");

    /** A shader's top-level pieces in canonical form, in order (comments gone, so empty for a file of comments). */
    static List<String> pieces(String src) {
        String s = stripComments(src);
        List<String> out = new ArrayList<>();
        StringBuilder code = new StringBuilder();
        int depth = 0;
        boolean closed = false;
        for (String line : s.split("\n", -1)) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            if (t.startsWith("#")) {
                if (depth == 0) {
                    if (closed || !code.isEmpty()) flush(out, code);
                    closed = false;
                    out.add(canonical(t));
                } else {
                    code.append('\n').append(canonical(t)).append('\n');
                }
                continue;
            }
            Matcher m = TOKEN.matcher(t);
            while (m.find()) {
                String tok = m.group();
                if (closed) {
                    closed = false;
                    if (tok.equals(";")) {
                        code.append(';');
                        flush(out, code);
                        continue;
                    }
                    flush(out, code);
                }
                append(code, tok);
                if (tok.equals("{")) depth++;
                else if (tok.equals("}") && --depth == 0) closed = true;
                else if (tok.equals(";") && depth == 0) flush(out, code);
            }
        }
        if (!code.isEmpty()) flush(out, code);
        return out;
    }

    /** One directive line in canonical form. */
    private static String canonical(String line) {
        StringBuilder b = new StringBuilder();
        Matcher m = TOKEN.matcher(line);
        while (m.find()) append(b, m.group());
        return b.toString();
    }

    private static void append(StringBuilder b, String tok) {
        if (!b.isEmpty() && word(b.charAt(b.length() - 1)) && word(tok.charAt(0))) b.append(' ');
        b.append(tok);
    }

    private static boolean word(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.';
    }

    private static void flush(List<String> out, StringBuilder code) {
        String piece = code.toString().strip();
        if (!piece.isEmpty()) out.add(piece);
        code.setLength(0);
    }

    private static String stripComments(String src) {
        StringBuilder b = new StringBuilder(src.length());
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                // Keep the line breaks a block comment spans, so the directives after it stay on lines of their own.
                String gone = src.substring(i, end < 0 ? n : end + 2);
                for (char g : gone.toCharArray()) if (g == '\n') b.append('\n');
                b.append(' ');
                i = end < 0 ? n : end + 2;
            } else {
                b.append(c == '\r' ? '\n' : c);
                i++;
            }
        }
        return b.toString();
    }

    /** A conditional directive: which pieces sit under which condition is not compared, only that the pieces are there. */
    private static boolean conditional(String piece) {
        return piece.startsWith("#if") || piece.startsWith("#el") || piece.startsWith("#endif");
    }

    /** The include id [piece] imports ("minecraft:fog.glsl"), or null when it is not an import. */
    static String importOf(String piece) {
        Matcher m = IMPORT.matcher(piece);
        if (!m.matches()) return null;
        return (m.group(1) == null ? "minecraft" : m.group(1)) + ":" + m.group(2);
    }

    /** The pack path of the include [id] ("minecraft:fog.glsl" is assets/minecraft/shaders/include/fog.glsl). */
    static String includePath(String id) {
        int colon = id.indexOf(':');
        return "assets/" + id.substring(0, colon) + "/shaders/include/" + id.substring(colon + 1);
    }

    /** What is missing from a copy that does not carry ours, for a log line. */
    record Missing(String what) {}

    /**
     * Whether [theirs] carries all of [ours] (see the class). [include] gives the text of an include by its id ("ns:path"),
     * or null when the pack has none (the game's own, which is then taken as it is). Null when it does, else what is
     * missing first.
     */
    static Missing carries(String ours, String theirs, Function<String, String> include) {
        List<String> our = pieces(ours);
        Set<String> their = new LinkedHashSet<>();
        expand(theirs, include, their, new HashSet<>());
        Map<String, String> theirFunctions = functions(their);
        String ourMain = null;
        for (String p : our) {
            if (conditional(p) || p.startsWith("#version")) continue;
            Matcher f = FUNCTION.matcher(p);
            if (f.matches() && f.group(1).equals("main") && f.group(2).isEmpty()) {
                ourMain = f.group(3);
                continue;
            }
            if (their.contains(p) || equivalent(p, their)) continue;
            return new Missing(describe(p));
        }
        if (ourMain == null) return null;
        Set<String> reached = reachable(theirFunctions);
        for (String name : reached) {
            Matcher f = FUNCTION.matcher(theirFunctions.get(name));
            if (f.matches() && f.group(2).isEmpty() && f.group(3).equals(ourMain)) return null;
        }
        // Our main only calls our functions (a hook's main): each of them, run from theirs.
        Set<String> ourFunctions = functions(our).keySet();
        List<String> calls = new ArrayList<>();
        for (String stmt : ourMain.split(";")) {
            Matcher c = BARE_CALL.matcher(stmt.strip());
            if (!c.matches() || !ourFunctions.contains(c.group(1))) return new Missing("a call of MC GUI's main from the shader's own main");
            calls.add(c.group(1));
        }
        for (String call : calls) {
            if (!reached.contains(call)) return new Missing("a call of " + call + "() from the shader's own main");
        }
        return null;
    }

    private static String describe(String piece) {
        Matcher f = FUNCTION.matcher(piece);
        if (f.matches()) return "the function " + f.group(1) + "()";
        String one = piece.length() > 80 ? piece.substring(0, 77) + "..." : piece;
        return "\"" + one + "\"";
    }

    /** A uniform block of a game include against an import of that include, either way round. */
    private static boolean equivalent(String piece, Set<String> their) {
        Matcher b = BLOCK.matcher(piece);
        if (b.find() && BLOCK_INCLUDES.containsKey(b.group(1))) {
            String id = BLOCK_INCLUDES.get(b.group(1));
            for (String p : their) if (id.equals(importOf(p))) return true;
            return false;
        }
        String id = importOf(piece);
        if (id == null) return false;
        for (Map.Entry<String, String> e : BLOCK_INCLUDES.entrySet()) {
            if (!e.getValue().equals(id)) continue;
            for (String p : their) {
                Matcher tb = BLOCK.matcher(p);
                if (tb.find() && tb.group(1).equals(e.getKey())) return true;
            }
        }
        return false;
    }

    /** [src]'s pieces into [into], each import of an include the pack has followed by that include's pieces. */
    private static void expand(String src, Function<String, String> include, Set<String> into, Set<String> seen) {
        for (String p : pieces(src)) {
            into.add(p);
            String id = importOf(p);
            if (id == null || !seen.add(id)) continue;
            String text = include.apply(id);
            if (text != null) expand(text, include, into, seen);
        }
    }

    /** The functions among [pieces], by name (an overload keeps the first). */
    private static Map<String, String> functions(Iterable<String> pieces) {
        Map<String, String> out = new HashMap<>();
        for (String p : pieces) {
            if (p.startsWith("#") || p.endsWith(";")) continue;
            Matcher f = FUNCTION.matcher(p);
            if (f.matches()) out.putIfAbsent(f.group(1), p);
        }
        return out;
    }

    /** The functions [functions] calls from main, main included, following every call. */
    private static Set<String> reachable(Map<String, String> functions) {
        Set<String> out = new LinkedHashSet<>();
        if (!functions.containsKey("main")) return out;
        ArrayDeque<String> todo = new ArrayDeque<>(List.of("main"));
        while (!todo.isEmpty()) {
            String name = todo.pop();
            if (!out.add(name)) continue;
            Matcher f = FUNCTION.matcher(functions.get(name));
            if (!f.matches()) continue;
            Matcher c = CALL.matcher(f.group(3));
            while (c.find()) if (functions.containsKey(c.group(1)) && !out.contains(c.group(1))) todo.push(c.group(1));
        }
        return out;
    }
}
