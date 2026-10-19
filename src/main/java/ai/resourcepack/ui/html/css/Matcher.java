package ai.resourcepack.ui.html.css;

import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the rules that match elements, for one style pass (one thread, one hover state, one size).
 * The pass walks the tree depth first and tells {@link #ancestors} which elements it is inside; for each
 * element it asks once per sheet and kind. A rule is tried only if it is in a bucket the element is in
 * (its id, a class, an attribute, its tag, being the root, or none), its media holds, and the ancestor
 * filter allows it; only then is the selector itself matched.
 */
public final class Matcher {
    public final AncestorFilter ancestors = new AncestorFilter();
    private final Selector.State state;
    private final boolean quirks;
    private final double width, height;

    /** The element the keys below are for, read once however many sheets and kinds ask. */
    private Element current;
    private String tag, id;
    private boolean root;
    /** The bits of the element's names, as {@link Selector#subjectMask} has them. */
    private long mask;
    private final List<String> classes = new ArrayList<>(), attrs = new ArrayList<>();

    public Matcher(Selector.State state, boolean quirks, double width, double height) {
        this.state = state;
        this.quirks = quirks;
        this.width = width;
        this.height = height;
    }

    /**
     * Adds the rules of [sheet] styling [e] as [kind] that match it: to [matched] those whose selector
     * does not depend on hover state, and to [dynamic] those that do and could match, untried, for
     * {@link #matches} (so a later pass with another hover state can try only those again).
     */
    public void collect(Stylesheet sheet, Element e, int kind, List<Stylesheet.Rule> matched, List<Stylesheet.Rule> dynamic) {
        Stylesheet.Index ix = sheet.kinds[kind];
        if (ix == null) return;
        if (e != current) keys(e);
        if (id != null) bucket(ix.byId.get(id), e, matched, dynamic);
        if (root) bucket(ix.root, e, matched, dynamic);
        if (!ix.byClass.isEmpty()) for (String c : classes) bucket(ix.byClass.get(c), e, matched, dynamic);
        if (!ix.byAttr.isEmpty()) for (String a : attrs) bucket(ix.byAttr.get(a), e, matched, dynamic);
        bucket(ix.byTag.get(tag), e, matched, dynamic);
        bucket(ix.universal, e, matched, dynamic);
    }

    /** Whether [r] matches [e] in this pass's hover state. */
    public boolean matches(Stylesheet.Rule r, Element e) {
        return r.selector().matches(e, state, quirks);
    }

    private void bucket(List<Stylesheet.Rule> rules, Element e, List<Stylesheet.Rule> matched, List<Stylesheet.Rule> dynamic) {
        if (rules == null) return;
        for (int i = 0, n = rules.size(); i < n; i++) {
            Stylesheet.Rule r = rules.get(i);
            if ((r.selector().subjectMask & ~mask) != 0) continue;
            if (r.media() != null && !r.media().matches(width, height)) continue;
            Selector s = r.selector();
            if (s.ancestorHashes.length > 0 && !ancestors.mayHave(s.ancestorHashes)) continue;
            if (s.dynamic) dynamic.add(r);
            else if (s.matches(e, state, quirks)) matched.add(r);
        }
    }

    /** The bucket keys of [e]: each class and attribute name once, all ASCII lower case. */
    private void keys(Element e) {
        current = e;
        tag = e.normalName();
        String i = e.id();
        id = i.isEmpty() ? null : Stylesheet.lower(i);
        root = e.parent() == null || e.parent() instanceof org.jsoup.nodes.Document;
        classes.clear();
        attrs.clear();
        mask = Selector.bit(AncestorFilter.hash(tag, AncestorFilter.TAG));
        if (id != null) mask |= Selector.bit(AncestorFilter.hash(id, AncestorFilter.ID));
        if (e.attributesSize() == 0) return;
        for (Attribute a : e.attributes()) {
            String key = Stylesheet.lower(a.getKey());
            if (!attrs.contains(key)) attrs.add(key);
            mask |= Selector.bit(AncestorFilter.hash(key, AncestorFilter.ATTR));
        }
        String list = e.attr("class");
        int n = list.length();
        for (int k = 0; k < n; ) {
            while (k < n && Selector.space(list.charAt(k))) k++;
            int start = k;
            while (k < n && !Selector.space(list.charAt(k))) k++;
            if (k > start) {
                String c = Stylesheet.lower(list.substring(start, k));
                if (!classes.contains(c)) classes.add(c);
                mask |= Selector.bit(AncestorFilter.hash(c, AncestorFilter.CLASS));
            }
        }
    }
}
