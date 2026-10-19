package ai.resourcepack.ui.html.css;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every rule a page's styles add up to, in order, indexed by what their rightmost selector part
 * needs (an id, a class, a tag) so matching an element only tries the rules that could apply.
 */
public final class Stylesheet {
    public final List<FontFace> fontFaces = new ArrayList<>();
    final List<Rule> all = new ArrayList<>();
    final Map<String, List<Rule>> byId = new HashMap<>();
    final Map<String, List<Rule>> byClass = new HashMap<>();
    final Map<String, List<Rule>> byTag = new HashMap<>();
    final List<Rule> universal = new ArrayList<>();
    /** Whether any rule depends on :hover or :active, so a page without one never re-renders on hover. */
    public boolean dynamic;
    /** The selector parts that say :hover (or :active): what decides whether a move of the pointer changes anything. */
    private final List<Selector.Compound> hoverParts = new ArrayList<>(), activeParts = new ArrayList<>();

    /** One selector of a rule, with its declarations: a rule with three selectors is three of these. */
    public record Rule(Selector selector, List<Declaration> declarations, Media media, int order) {}

    void add(List<Selector> selectors, List<Declaration> declarations, Media media) {
        for (Selector s : selectors) {
            Rule r = new Rule(s, declarations, media, all.size());
            all.add(r);
            if (s.dynamic()) dynamic = true;
            for (Selector.Compound c : s.compounds) {
                for (Selector.Pseudo p : c.pseudos) {
                    if (p.name().equals("hover") && !hoverParts.contains(c)) hoverParts.add(c);
                    if (p.name().equals("active") && !activeParts.contains(c)) activeParts.add(c);
                }
            }
            Selector.Compound key = s.subject();
            // Keys in ASCII lower case, so quirks mode, where ids and classes ignore case, finds them too.
            if (key.id != null) byId.computeIfAbsent(lower(key.id), k -> new ArrayList<>()).add(r);
            else if (!key.classes.isEmpty()) byClass.computeIfAbsent(lower(key.classes.get(0)), k -> new ArrayList<>()).add(r);
            else if (key.tag != null) byTag.computeIfAbsent(key.tag, k -> new ArrayList<>()).add(r);
            else universal.add(r);
        }
    }

    /** [s] with A-Z as a-z: the same string when there are none. */
    static String lower(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                char[] a = s.toCharArray();
                for (int k = i; k < a.length; k++) if (a[k] >= 'A' && a[k] <= 'Z') a[k] += 32;
                return new String(a);
            }
        }
        return s;
    }

    public int size() {
        return all.size();
    }

    /**
     * The elements whose :hover (or :active) state would change how the page looks, of [e] and its
     * ancestors, innermost first. Two pointer positions with the same list draw the same picture, so
     * moving the pointer within a button redraws nothing.
     */
    public List<org.jsoup.nodes.Element> stateKey(org.jsoup.nodes.Element e, boolean active) {
        List<Selector.Compound> parts = active ? activeParts : hoverParts;
        if (e == null || parts.isEmpty()) return List.of();
        org.jsoup.nodes.Document doc = e.ownerDocument();
        boolean quirks = doc != null && doc.quirksMode() == org.jsoup.nodes.Document.QuirksMode.quirks;
        List<org.jsoup.nodes.Element> out = new ArrayList<>();
        for (org.jsoup.nodes.Element p = e; p != null; p = p.parent()) {
            Selector.State st = active ? new Selector.State(java.util.Set.of(), java.util.Set.of(p)) : new Selector.State(java.util.Set.of(p), java.util.Set.of());
            for (Selector.Compound c : parts) {
                if (Selector.matchCompound(c, p, st, quirks)) {
                    out.add(p);
                    break;
                }
            }
        }
        return out;
    }

    /** Every rule, in order. */
    public List<Rule> rules() {
        return all;
    }

    /** The rules that might match an element with this tag, id and classes, in no particular order. */
    public void candidates(String tag, String id, Iterable<String> classes, List<Rule> out) {
        out.addAll(universal);
        List<Rule> t = byTag.get(tag.toLowerCase(Locale.ROOT));
        if (t != null) out.addAll(t);
        if (id != null && !id.isEmpty()) {
            List<Rule> i = byId.get(lower(id));
            if (i != null) out.addAll(i);
        }
        for (String c : classes) {
            List<Rule> r = byClass.get(lower(c));
            if (r != null) out.addAll(r);
        }
    }
}
