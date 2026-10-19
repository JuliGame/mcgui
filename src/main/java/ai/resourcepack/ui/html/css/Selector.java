package ai.resourcepack.ui.html.css;

import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One complex selector: compounds joined by combinators, matched right to left. Supports type,
 * universal, id, class and attribute selectors, the four combinators, the structural pseudo-classes,
 * {@code :not() :is() :where()}, {@code :hover :active}, form states, and the {@code ::before ::after}
 * pseudo-elements. An unknown pseudo-class makes the selector match nothing, as a browser would drop it.
 */
public final class Selector {
    /** Which elements are under the pointer (and their ancestors), and which are being pressed. */
    public record State(Set<Element> hovered, Set<Element> active) {
        public static final State NONE = new State(Set.of(), Set.of());
    }

    final List<Compound> compounds;     // left to right
    final List<Character> combinators;  // between compounds: ' ', '>', '+', '~'
    public final int specificity;
    public final String pseudoElement;  // "before", "after", "marker", "placeholder", or null

    private Selector(List<Compound> compounds, List<Character> combinators) {
        this.compounds = compounds;
        this.combinators = combinators;
        int s = 0;
        String pe = null;
        for (Compound c : compounds) {
            s += c.specificity();
            if (c.pseudoElement != null) pe = c.pseudoElement;
        }
        this.specificity = s;
        this.pseudoElement = pe;
    }

    Compound subject() {
        return compounds.get(compounds.size() - 1);
    }

    boolean dynamic() {
        for (Compound c : compounds) if (c.dynamic()) return true;
        return false;
    }

    public static final class Compound {
        String tag;            // lower case, null for any
        String id;
        final List<String> classes = new ArrayList<>();
        final List<String[]> attrs = new ArrayList<>(); // {name, op, value, flags}
        final List<Pseudo> pseudos = new ArrayList<>();
        String pseudoElement;
        boolean never;         // an unsupported pseudo-class

        int specificity() {
            int s = (id != null ? 10000 : 0) + (classes.size() + attrs.size()) * 100 + (tag != null ? 1 : 0) + (pseudoElement != null ? 1 : 0);
            for (Pseudo p : pseudos) s += p.specificity();
            return s;
        }

        boolean dynamic() {
            for (Pseudo p : pseudos) {
                if (p.name.equals("hover") || p.name.equals("active")) return true;
                if (p.args != null) for (Selector s : p.args) if (s.dynamic()) return true;
            }
            return false;
        }
    }

    record Pseudo(String name, String arg, List<Selector> args, int[] nth) {
        int specificity() {
            return switch (name) {
                case "where" -> 0;
                case "is", "not", "matches", "has" -> {
                    int max = 0;
                    if (args != null) for (Selector s : args) max = Math.max(max, s.specificity);
                    yield max;
                }
                default -> 100;
            };
        }
    }

    // ---------------------------------------------------------------- parsing

    public static List<Selector> parseList(String text) {
        List<Selector> out = new ArrayList<>();
        for (String part : Values.splitCommas(text)) {
            Selector s = parse(part.trim());
            if (s != null) out.add(s);
        }
        return out;
    }

    static Selector parse(String text) {
        if (text.isEmpty()) return null;
        List<Compound> compounds = new ArrayList<>();
        List<Character> combinators = new ArrayList<>();
        int i = 0;
        char pending = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!compounds.isEmpty() && pending == 0) pending = ' ';
                i++;
                continue;
            }
            if (c == '>' || c == '+' || c == '~') {
                if (compounds.isEmpty()) return null;
                pending = c;
                i++;
                continue;
            }
            Compound compound = new Compound();
            i = compound(text, i, compound);
            if (i < 0) return null;
            if (!compounds.isEmpty()) combinators.add(pending == 0 ? ' ' : pending);
            pending = 0;
            compounds.add(compound);
        }
        if (compounds.isEmpty() || (pending != 0 && pending != ' ')) return null;
        return new Selector(compounds, combinators);
    }

    /** Reads one compound starting at [i]; returns where it stopped, or -1 if it cannot be read. */
    private static int compound(String t, int i, Compound c) {
        int n = t.length();
        boolean any = false;
        while (i < n) {
            char ch = t.charAt(i);
            if (ch == '*') {
                i++;
                any = true;
            } else if (ch == '#') {
                int end = ident(t, i + 1);
                if (end == i + 1) return -1;
                c.id = unescape(t.substring(i + 1, end));
                i = end;
                any = true;
            } else if (ch == '.') {
                int end = ident(t, i + 1);
                if (end == i + 1) return -1;
                c.classes.add(unescape(t.substring(i + 1, end)));
                i = end;
                any = true;
            } else if (ch == '[') {
                int close = CssParser.scan(t, i + 1, "]");
                if (close >= n) return -1;
                String[] attr = attribute(t.substring(i + 1, close));
                if (attr == null) return -1;
                c.attrs.add(attr);
                i = close + 1;
                any = true;
            } else if (ch == ':') {
                boolean element = i + 1 < n && t.charAt(i + 1) == ':';
                int start = i + (element ? 2 : 1);
                int end = ident(t, start);
                String name = t.substring(start, end).toLowerCase(Locale.ROOT);
                String arg = null;
                if (end < n && t.charAt(end) == '(') {
                    int close = closeParen(t, end);
                    if (close < 0) return -1;
                    arg = t.substring(end + 1, close).trim();
                    end = close + 1;
                }
                i = end;
                any = true;
                if (element || name.equals("before") || name.equals("after") || name.equals("first-line") || name.equals("first-letter")) {
                    c.pseudoElement = name;
                    if (!Set.of("before", "after", "marker", "placeholder").contains(name)) c.never = true;
                    continue;
                }
                pseudo(name, arg, c);
            } else if (Character.isLetter(ch) || ch == '_' || ch == '-' || ch == '\\') {
                if (any) break;
                int end = ident(t, i);
                // Nothing an identifier can start with (a lone '-' or '\\'): unreadable, not a compound
                // that reads nothing, which left parse() looping on the same character for ever.
                if (end == i) return -1;
                c.tag = t.substring(i, end).toLowerCase(Locale.ROOT);
                i = end;
                any = true;
            } else {
                break;
            }
        }
        return any ? i : -1;
    }

    private static void pseudo(String name, String arg, Compound c) {
        switch (name) {
            case "not", "is", "where", "matches", "any", "-webkit-any" -> {
                // Selectors in selectors are read recursively: one nested past MAX_NESTING never matches.
                if (arg != null && nesting(arg) > MAX_NESTING) {
                    c.never = true;
                    return;
                }
                List<Selector> args = arg == null ? List.of() : parseList(arg);
                if (args.isEmpty() && !name.equals("not")) c.never = true;
                c.pseudos.add(new Pseudo(name.equals("not") || name.equals("where") ? name : "is", arg, args, null));
            }
            case "nth-child", "nth-last-child", "nth-of-type", "nth-last-of-type" -> {
                String a = arg == null ? "" : arg;
                // "2n+1 of .x" is read as its An+B part.
                int of = a.indexOf(" of ");
                int[] nth = nth(of >= 0 ? a.substring(0, of) : a);
                if (nth == null) c.never = true;
                else c.pseudos.add(new Pseudo(name, arg, null, nth));
            }
            case "hover", "active", "first-child", "last-child", "only-child", "first-of-type", "last-of-type", "only-of-type",
                 "root", "empty", "link", "any-link", "checked", "disabled", "enabled", "required", "optional",
                 "placeholder-shown", "read-only", "read-write", "defined", "scope" -> c.pseudos.add(new Pseudo(name, arg, null, null));
            // States a static page is never in.
            case "focus", "focus-within", "focus-visible", "visited", "target", "indeterminate", "invalid", "valid",
                 "has", "lang", "dir", "fullscreen", "autofill", "-webkit-autofill", "user-invalid", "popover-open", "open" -> {
                if (name.equals("valid") || name.equals("dir")) c.pseudos.add(new Pseudo("always", arg, null, null));
                else c.never = true;
            }
            default -> c.never = true;
        }
    }

    private static final int MAX_NESTING = 32;

    /** How deep the parentheses in [s] go. */
    private static int nesting(String s) {
        int depth = 0, max = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') max = Math.max(max, ++depth);
            else if (ch == ')') depth--;
        }
        return max;
    }

    /** {a, b} for An+B, odd, even. */
    private static int[] nth(String s) {
        s = s.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.equals("odd")) return new int[]{2, 1};
        if (s.equals("even")) return new int[]{2, 0};
        try {
            int n = s.indexOf('n');
            if (n < 0) return new int[]{0, Integer.parseInt(s)};
            String a = s.substring(0, n);
            int av = a.isEmpty() || a.equals("+") ? 1 : a.equals("-") ? -1 : Integer.parseInt(a);
            String b = s.substring(n + 1);
            int bv = b.isEmpty() ? 0 : Integer.parseInt(b.startsWith("+") ? b.substring(1) : b);
            return new int[]{av, bv};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String[] attribute(String inside) {
        inside = inside.trim();
        int op = -1;
        for (int k = 0; k < inside.length(); k++) {
            char ch = inside.charAt(k);
            if (ch == '=' || ((ch == '~' || ch == '|' || ch == '^' || ch == '$' || ch == '*') && k + 1 < inside.length() && inside.charAt(k + 1) == '=')) {
                op = k;
                break;
            }
        }
        if (op < 0) return new String[]{inside.toLowerCase(Locale.ROOT), null, null, ""};
        String name = inside.substring(0, op).trim().toLowerCase(Locale.ROOT);
        String operator = inside.charAt(op) == '=' ? "=" : inside.substring(op, op + 2);
        String value = inside.substring(op + operator.length()).trim();
        String flags = "";
        if (value.endsWith(" i") || value.endsWith(" s")) {
            flags = value.substring(value.length() - 1);
            value = value.substring(0, value.length() - 2).trim();
        }
        return new String[]{name, operator, CssParser.unquote(value), flags};
    }

    private static int closeParen(String t, int open) {
        int depth = 0;
        for (int k = open; k < t.length(); k++) {
            char ch = t.charAt(k);
            if (ch == '(') depth++;
            else if (ch == ')' && --depth == 0) return k;
        }
        return -1;
    }

    private static int ident(String t, int i) {
        while (i < t.length()) {
            char ch = t.charAt(i);
            if (ch == '\\' && i + 1 < t.length()) {
                i += 2;
                continue;
            }
            if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch > 127) i++;
            else break;
        }
        return i;
    }

    /** Tailwind-style escapes: {@code .md\:flex}, {@code .w-1\/2}. */
    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) b.append(s.charAt(++i));
            else b.append(ch);
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- matching

    public boolean matches(Element e, State state) {
        return matchFrom(compounds.size() - 1, e, state) == MATCH;
    }

    /**
     * What trying compound [index] (and those left of it) on an element found: a match, a failure only
     * there, or a failure that no sibling further back (no ancestor further up) can mend. The last two
     * stop the search, as WebKit's SelectorChecker does: without them {@code ol ul ul} tried on a chain of
     * nested lists walks every ancestor from every ancestor, and a page of nested lists styles for minutes.
     */
    private static final int MATCH = 0, FAILS_HERE = 1, FAILS_ALL_SIBLINGS = 2, FAILS_COMPLETELY = 3;

    private int matchFrom(int index, Element e, State state) {
        if (!matchCompound(compounds.get(index), e, state)) return FAILS_HERE;
        if (index == 0) return MATCH;
        char comb = combinators.get(index - 1);
        switch (comb) {
            case '>' -> {
                Element p = e.parent();
                return p == null ? FAILS_COMPLETELY : matchFrom(index - 1, p, state);
            }
            case '+' -> {
                Element s = e.previousElementSibling();
                return s == null ? FAILS_ALL_SIBLINGS : matchFrom(index - 1, s, state);
            }
            case '~' -> {
                for (Element s = e.previousElementSibling(); s != null; s = s.previousElementSibling()) {
                    int r = matchFrom(index - 1, s, state);
                    if (r != FAILS_HERE) return r;
                }
                return FAILS_ALL_SIBLINGS;
            }
            default -> {
                for (Element p = e.parent(); p != null; p = p.parent()) {
                    int r = matchFrom(index - 1, p, state);
                    if (r == MATCH || r == FAILS_COMPLETELY) return r;
                }
                return FAILS_COMPLETELY;
            }
        }
    }

    static boolean matchCompound(Compound c, Element e, State state) {
        if (c.never) return false;
        if (c.tag != null && !c.tag.equals(e.normalName())) return false;
        if (c.id != null && !c.id.equals(e.id())) return false;
        for (String cls : c.classes) if (!e.hasClass(cls)) return false;
        for (String[] a : c.attrs) if (!attr(e, a)) return false;
        for (Pseudo p : c.pseudos) if (!pseudo(p, e, state)) return false;
        return true;
    }

    private static boolean attr(Element e, String[] a) {
        if (!e.hasAttr(a[0])) return false;
        if (a[1] == null) return true;
        String v = e.attr(a[0]);
        String want = a[2];
        if (a[3].equals("i")) {
            v = v.toLowerCase(Locale.ROOT);
            want = want.toLowerCase(Locale.ROOT);
        }
        return switch (a[1]) {
            case "=" -> v.equals(want);
            case "~=" -> List.of(v.split("\\s+")).contains(want);
            case "|=" -> v.equals(want) || v.startsWith(want + "-");
            case "^=" -> !want.isEmpty() && v.startsWith(want);
            case "$=" -> !want.isEmpty() && v.endsWith(want);
            case "*=" -> !want.isEmpty() && v.contains(want);
            default -> false;
        };
    }

    private static boolean pseudo(Pseudo p, Element e, State state) {
        switch (p.name) {
            case "always":
                return true;
            case "hover":
                return state.hovered().contains(e);
            case "active":
                return state.active().contains(e);
            case "not":
                for (Selector s : p.args) if (s.matches(e, state)) return false;
                return true;
            case "is":
            case "where":
                for (Selector s : p.args) if (s.matches(e, state)) return true;
                return false;
            case "root":
                return e.parent() == null || e.parent() instanceof org.jsoup.nodes.Document;
            case "scope":
                return e.normalName().equals("html");
            case "empty":
                return e.childNodeSize() == 0 || (e.children().isEmpty() && e.wholeText().isEmpty());
            case "link":
            case "any-link":
                return (e.normalName().equals("a") || e.normalName().equals("area")) && e.hasAttr("href");
            case "checked":
                return e.hasAttr("checked") || e.hasAttr("selected");
            case "disabled":
                return e.hasAttr("disabled");
            case "enabled":
                return !e.hasAttr("disabled") && isFormControl(e);
            case "required":
                return e.hasAttr("required");
            case "optional":
                return !e.hasAttr("required") && isFormControl(e);
            case "placeholder-shown":
                return e.hasAttr("placeholder") && e.attr("value").isEmpty();
            case "read-only":
                return !isFormControl(e) || e.hasAttr("readonly");
            case "read-write":
                return isFormControl(e) && !e.hasAttr("readonly");
            case "defined":
                return true;
            case "first-child":
                return e.previousElementSibling() == null && hasParent(e);
            case "last-child":
                return e.nextElementSibling() == null && hasParent(e);
            case "only-child":
                return e.previousElementSibling() == null && e.nextElementSibling() == null && hasParent(e);
            case "first-of-type":
                return typeIndex(e, false) == 1;
            case "last-of-type":
                return typeIndex(e, true) == 1;
            case "only-of-type":
                return typeIndex(e, false) == 1 && typeIndex(e, true) == 1;
            case "nth-child":
                return nthMatch(p.nth, e.elementSiblingIndex() + 1);
            case "nth-last-child":
                return nthMatch(p.nth, siblings(e) - e.elementSiblingIndex());
            case "nth-of-type":
                return nthMatch(p.nth, typeIndex(e, false));
            case "nth-last-of-type":
                return nthMatch(p.nth, typeIndex(e, true));
            default:
                return false;
        }
    }

    private static boolean hasParent(Element e) {
        return e.parent() != null;
    }

    private static boolean isFormControl(Element e) {
        return switch (e.normalName()) {
            case "input", "button", "select", "textarea", "option", "fieldset" -> true;
            default -> false;
        };
    }

    private static int siblings(Element e) {
        return e.parent() == null ? 1 : e.parent().childrenSize();
    }

    /** 1-based position among siblings of the same tag, from the end if [last]. */
    private static int typeIndex(Element e, boolean last) {
        int n = 1;
        String tag = e.normalName();
        Element s = last ? e.nextElementSibling() : e.previousElementSibling();
        while (s != null) {
            if (s.normalName().equals(tag)) n++;
            s = last ? s.nextElementSibling() : s.previousElementSibling();
        }
        return n;
    }

    private static boolean nthMatch(int[] ab, int index) {
        int a = ab[0], b = ab[1];
        if (a == 0) return index == b;
        int diff = index - b;
        return diff / a >= 0 && diff % a == 0;
    }
}
