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

    /** One selector of a rule, with its declarations: a rule with three selectors is three of these. */
    public record Rule(Selector selector, List<Declaration> declarations, Media media, int order) {}

    void add(List<Selector> selectors, List<Declaration> declarations, Media media) {
        for (Selector s : selectors) {
            Rule r = new Rule(s, declarations, media, all.size());
            all.add(r);
            if (s.dynamic()) dynamic = true;
            Selector.Compound key = s.subject();
            if (key.id != null) byId.computeIfAbsent(key.id, k -> new ArrayList<>()).add(r);
            else if (!key.classes.isEmpty()) byClass.computeIfAbsent(key.classes.get(0), k -> new ArrayList<>()).add(r);
            else if (key.tag != null) byTag.computeIfAbsent(key.tag, k -> new ArrayList<>()).add(r);
            else universal.add(r);
        }
    }

    public int size() {
        return all.size();
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
            List<Rule> i = byId.get(id);
            if (i != null) out.addAll(i);
        }
        for (String c : classes) {
            List<Rule> r = byClass.get(c);
            if (r != null) out.addAll(r);
        }
    }
}
