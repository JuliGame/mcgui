package ai.resourcepack.ui.html.css;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Turns shorthands into the longhands the cascade works on. Called after {@code var()} substitution,
 * because a shorthand's parts cannot be told apart until the variables in it are known. A property
 * that is not a shorthand passes through unchanged.
 */
public final class Shorthands {
    private Shorthands() {}

    private static final String[] SIDES = {"top", "right", "bottom", "left"};
    private static final Set<String> BORDER_STYLES = Set.of("none", "hidden", "dotted", "dashed", "solid", "double", "groove", "ridge", "inset", "outset");
    private static final Set<String> WIDE = Set.of("inherit", "initial", "unset", "revert", "revert-layer");

    public static void expand(String prop, String value, BiConsumer<String, String> out) {
        String v = value.trim();
        boolean wide = WIDE.contains(v.toLowerCase(Locale.ROOT));
        switch (prop) {
            case "margin", "padding" -> box(prop + "-%s", v, wide, out);
            case "inset" -> box("%s", v, wide, out);
            case "border-width", "border-style", "border-color" -> {
                String kind = prop.substring(7);
                box("border-%s-" + kind, v, wide, out);
            }
            case "border" -> {
                for (String side : SIDES) border("border-" + side, v, wide, out);
            }
            case "border-top", "border-right", "border-bottom", "border-left" -> border(prop, v, wide, out);
            case "border-inline", "border-block" -> {
                String[] sides = prop.endsWith("inline") ? new String[]{"left", "right"} : new String[]{"top", "bottom"};
                for (String side : sides) border("border-" + side, v, wide, out);
            }
            case "border-inline-start" -> border("border-left", v, wide, out);
            case "border-inline-end" -> border("border-right", v, wide, out);
            case "border-block-start" -> border("border-top", v, wide, out);
            case "border-block-end" -> border("border-bottom", v, wide, out);
            case "border-radius" -> radius(v, wide, out);
            case "margin-inline", "padding-inline", "margin-block", "padding-block" -> {
                String base = prop.substring(0, prop.indexOf('-'));
                List<String> p = Values.splitSpaces(v);
                String a = p.isEmpty() ? v : p.get(0), b = p.size() > 1 ? p.get(1) : a;
                boolean inline = prop.endsWith("inline");
                out.accept(base + (inline ? "-left" : "-top"), a);
                out.accept(base + (inline ? "-right" : "-bottom"), b);
            }
            case "margin-inline-start", "padding-inline-start" -> out.accept(prop.substring(0, prop.indexOf('-')) + "-left", v);
            case "margin-inline-end", "padding-inline-end" -> out.accept(prop.substring(0, prop.indexOf('-')) + "-right", v);
            case "margin-block-start", "padding-block-start" -> out.accept(prop.substring(0, prop.indexOf('-')) + "-top", v);
            case "margin-block-end", "padding-block-end" -> out.accept(prop.substring(0, prop.indexOf('-')) + "-bottom", v);
            case "inset-inline" -> {
                List<String> p = Values.splitSpaces(v);
                out.accept("left", p.isEmpty() ? v : p.get(0));
                out.accept("right", p.size() > 1 ? p.get(1) : p.isEmpty() ? v : p.get(0));
            }
            case "inset-block" -> {
                List<String> p = Values.splitSpaces(v);
                out.accept("top", p.isEmpty() ? v : p.get(0));
                out.accept("bottom", p.size() > 1 ? p.get(1) : p.isEmpty() ? v : p.get(0));
            }
            case "inline-size" -> out.accept("width", v);
            case "block-size" -> out.accept("height", v);
            case "min-inline-size" -> out.accept("min-width", v);
            case "max-inline-size" -> out.accept("max-width", v);
            case "min-block-size" -> out.accept("min-height", v);
            case "max-block-size" -> out.accept("max-height", v);
            case "background" -> background(v, wide, out);
            case "font" -> font(v, wide, out);
            case "flex" -> flex(v, wide, out);
            case "flex-flow" -> {
                for (String part : Values.splitSpaces(v)) {
                    String l = part.toLowerCase(Locale.ROOT);
                    out.accept(l.contains("wrap") ? "flex-wrap" : "flex-direction", part);
                }
            }
            case "gap", "grid-gap" -> {
                List<String> p = Values.splitSpaces(v);
                out.accept("row-gap", p.isEmpty() ? v : p.get(0));
                out.accept("column-gap", p.size() > 1 ? p.get(1) : p.isEmpty() ? v : p.get(0));
            }
            case "grid-row-gap" -> out.accept("row-gap", v);
            case "grid-column-gap" -> out.accept("column-gap", v);
            case "place-items" -> pair("align-items", "justify-items", v, out);
            case "place-content" -> pair("align-content", "justify-content", v, out);
            case "place-self" -> pair("align-self", "justify-self", v, out);
            case "grid-column", "grid-row" -> {
                String[] p = v.split("/");
                out.accept(prop + "-start", p[0].trim());
                out.accept(prop + "-end", p.length > 1 ? p[1].trim() : (p[0].trim().startsWith("span") || wide ? p[0].trim() : "auto"));
            }
            case "grid-area" -> {
                String[] p = v.split("/");
                if (p.length == 1 && !p[0].trim().matches("-?\\d+|span.*|auto")) {
                    out.accept("grid-area-name", p[0].trim());
                    return;
                }
                out.accept("grid-row-start", p[0].trim());
                out.accept("grid-column-start", p.length > 1 ? p[1].trim() : "auto");
                out.accept("grid-row-end", p.length > 2 ? p[2].trim() : "auto");
                out.accept("grid-column-end", p.length > 3 ? p[3].trim() : "auto");
            }
            case "grid-template" -> {
                String[] p = v.split("/");
                out.accept("grid-template-rows", p[0].trim());
                if (p.length > 1) out.accept("grid-template-columns", p[1].trim());
            }
            case "overflow" -> {
                List<String> p = Values.splitSpaces(v);
                out.accept("overflow-x", p.isEmpty() ? v : p.get(0));
                out.accept("overflow-y", p.size() > 1 ? p.get(1) : p.isEmpty() ? v : p.get(0));
            }
            case "list-style" -> {
                for (String part : Values.splitSpaces(v)) {
                    String l = part.toLowerCase(Locale.ROOT);
                    if (l.equals("inside") || l.equals("outside")) out.accept("list-style-position", l);
                    else if (l.startsWith("url(")) out.accept("list-style-image", part);
                    else out.accept("list-style-type", part);
                }
            }
            case "text-decoration" -> {
                StringBuilder lines = new StringBuilder();
                for (String part : Values.splitSpaces(v)) {
                    String l = part.toLowerCase(Locale.ROOT);
                    if (Set.of("underline", "overline", "line-through", "none").contains(l)) lines.append(l).append(' ');
                    else if (Set.of("solid", "double", "dotted", "dashed", "wavy").contains(l)) out.accept("text-decoration-style", l);
                    else if (Values.color(part, 0) != null) out.accept("text-decoration-color", part);
                    else out.accept("text-decoration-thickness", part);
                }
                if (!lines.isEmpty()) out.accept("text-decoration-line", lines.toString().trim());
            }
            case "outline" -> {} // never drawn: a static page has no focus ring
            case "columns", "transition", "animation" -> {}
            case "-webkit-text-fill-color" -> out.accept("-webkit-text-fill-color", v);
            default -> out.accept(prop, v);
        }
    }

    /** top, right, bottom, left from 1 to 4 values. [pattern] has a %s for the side. */
    private static void box(String pattern, String v, boolean wide, BiConsumer<String, String> out) {
        List<String> p = wide ? List.of(v) : Values.splitSpaces(v);
        if (p.isEmpty()) return;
        String t = p.get(0), r = p.size() > 1 ? p.get(1) : t, b = p.size() > 2 ? p.get(2) : t, l = p.size() > 3 ? p.get(3) : r;
        out.accept(String.format(pattern, "top"), t);
        out.accept(String.format(pattern, "right"), r);
        out.accept(String.format(pattern, "bottom"), b);
        out.accept(String.format(pattern, "left"), l);
    }

    private static void border(String side, String v, boolean wide, BiConsumer<String, String> out) {
        if (wide) {
            out.accept(side + "-width", v);
            out.accept(side + "-style", v);
            out.accept(side + "-color", v);
            return;
        }
        String width = "medium", style = "none", color = "currentcolor";
        for (String part : Values.splitSpaces(v)) {
            String l = part.toLowerCase(Locale.ROOT);
            if (BORDER_STYLES.contains(l)) style = l;
            else if (Values.color(part, 0) != null) color = part;
            else width = part;
        }
        out.accept(side + "-width", width);
        out.accept(side + "-style", style);
        out.accept(side + "-color", color);
    }

    private static void radius(String v, boolean wide, BiConsumer<String, String> out) {
        String[] corners = {"border-top-left-radius", "border-top-right-radius", "border-bottom-right-radius", "border-bottom-left-radius"};
        if (wide) {
            for (String c : corners) out.accept(c, v);
            return;
        }
        String[] halves = v.split("/");
        List<String> h = Values.splitSpaces(halves[0].trim());
        List<String> vert = halves.length > 1 ? Values.splitSpaces(halves[1].trim()) : h;
        String[] hs = four(h), vs = four(vert);
        for (int i = 0; i < 4; i++) out.accept(corners[i], hs[i].equals(vs[i]) ? hs[i] : hs[i] + " " + vs[i]);
    }

    private static String[] four(List<String> p) {
        if (p.isEmpty()) return new String[]{"0", "0", "0", "0"};
        String a = p.get(0), b = p.size() > 1 ? p.get(1) : a, c = p.size() > 2 ? p.get(2) : a, d = p.size() > 3 ? p.get(3) : b;
        return new String[]{a, b, c, d};
    }

    private static void pair(String first, String second, String v, BiConsumer<String, String> out) {
        List<String> p = Values.splitSpaces(v);
        out.accept(first, p.isEmpty() ? v : p.get(0));
        out.accept(second, p.size() > 1 ? p.get(1) : p.isEmpty() ? v : p.get(0));
    }

    private static void flex(String v, boolean wide, BiConsumer<String, String> out) {
        String l = v.toLowerCase(Locale.ROOT);
        if (wide) {
            out.accept("flex-grow", v);
            out.accept("flex-shrink", v);
            out.accept("flex-basis", v);
            return;
        }
        switch (l) {
            case "none" -> {
                out.accept("flex-grow", "0");
                out.accept("flex-shrink", "0");
                out.accept("flex-basis", "auto");
                return;
            }
            case "auto" -> {
                out.accept("flex-grow", "1");
                out.accept("flex-shrink", "1");
                out.accept("flex-basis", "auto");
                return;
            }
            case "initial" -> {
                out.accept("flex-grow", "0");
                out.accept("flex-shrink", "1");
                out.accept("flex-basis", "auto");
                return;
            }
            default -> {}
        }
        List<String> p = Values.splitSpaces(v);
        String grow = "1", shrink = "1", basis = "0%";
        List<String> numbers = new ArrayList<>();
        for (String part : p) {
            if (Values.number(part) != null) numbers.add(part);
            else basis = part;
        }
        if (!numbers.isEmpty()) grow = numbers.get(0);
        if (numbers.size() > 1) shrink = numbers.get(1);
        // "flex: 0" alone is a grow of 0, with the 0% basis the one-number form implies.
        out.accept("flex-grow", grow);
        out.accept("flex-shrink", shrink);
        out.accept("flex-basis", basis);
    }

    private static void font(String v, boolean wide, BiConsumer<String, String> out) {
        String l = v.toLowerCase(Locale.ROOT).trim();
        if (wide) {
            for (String p : new String[]{"font-style", "font-weight", "font-size", "line-height", "font-family"}) out.accept(p, v);
            return;
        }
        // System font keywords: a reasonable UI font.
        if (Set.of("caption", "icon", "menu", "message-box", "small-caption", "status-bar").contains(l)) {
            out.accept("font-family", "system-ui");
            out.accept("font-size", "13px");
            return;
        }
        List<String> p = Values.splitSpaces(v);
        int i = 0;
        String style = "normal", weight = "normal", lineHeight = "normal", size = null;
        for (; i < p.size(); i++) {
            String part = p.get(i);
            String pl = part.toLowerCase(Locale.ROOT);
            if (pl.equals("italic") || pl.equals("oblique")) style = pl;
            else if (pl.equals("bold") || pl.equals("bolder") || pl.equals("lighter") || pl.matches("[1-9]00")) weight = pl;
            else if (pl.equals("normal") || pl.equals("small-caps")) continue;
            else break;
        }
        if (i < p.size()) {
            size = p.get(i++);
            if (i < p.size() && p.get(i).equals("/")) {
                i++;
                if (i < p.size()) lineHeight = p.get(i++);
            } else if (size.contains("/")) {
                String[] s = size.split("/");
                size = s[0];
                lineHeight = s[1];
            }
        }
        StringBuilder family = new StringBuilder();
        for (; i < p.size(); i++) family.append(family.isEmpty() ? "" : " ").append(p.get(i));
        out.accept("font-style", style);
        out.accept("font-weight", weight);
        if (size != null) out.accept("font-size", size);
        out.accept("line-height", lineHeight);
        if (!family.isEmpty()) out.accept("font-family", family.toString());
    }

    private static void background(String v, boolean wide, BiConsumer<String, String> out) {
        if (wide) {
            for (String p : new String[]{"background-color", "background-image", "background-position", "background-size", "background-repeat"}) out.accept(p, v);
            return;
        }
        List<String> layers = Values.splitCommas(v);
        List<String> images = new ArrayList<>(), positions = new ArrayList<>(), sizes = new ArrayList<>(), repeats = new ArrayList<>(), clips = new ArrayList<>();
        String color = "transparent";
        for (int li = 0; li < layers.size(); li++) {
            String image = "none", position = "0% 0%", size = "auto", repeat = "repeat", clip = "border-box";
            List<String> parts = Values.splitSpaces(layers.get(li));
            StringBuilder pos = new StringBuilder();
            boolean afterSlash = false;
            StringBuilder sz = new StringBuilder();
            for (String part : parts) {
                String pl = part.toLowerCase(Locale.ROOT);
                if (pl.equals("/")) {
                    afterSlash = true;
                    continue;
                }
                if (pl.startsWith("url(") || pl.contains("gradient(") || pl.startsWith("image-set(")) image = part;
                else if (pl.equals("none")) image = "none";
                else if (pl.startsWith("repeat") || pl.equals("no-repeat") || pl.equals("space") || pl.equals("round")) repeat = repeat.equals("repeat") ? pl : repeat + " " + pl;
                else if (pl.equals("fixed") || pl.equals("scroll") || pl.equals("local")) continue;
                else if (pl.equals("border-box") || pl.equals("padding-box") || pl.equals("content-box") || pl.equals("text")) clip = pl;
                else if (li == layers.size() - 1 && Values.color(part, 0) != null) color = part;
                else if (afterSlash) sz.append(sz.isEmpty() ? "" : " ").append(part);
                else pos.append(pos.isEmpty() ? "" : " ").append(part);
            }
            if (!pos.isEmpty()) position = pos.toString();
            if (!sz.isEmpty()) size = sz.toString();
            images.add(image);
            positions.add(position);
            sizes.add(size);
            repeats.add(repeat);
            clips.add(clip);
        }
        out.accept("background-color", color);
        out.accept("background-image", String.join(", ", images));
        out.accept("background-position", String.join(", ", positions));
        out.accept("background-size", String.join(", ", sizes));
        out.accept("background-repeat", String.join(", ", repeats));
        out.accept("background-clip", String.join(", ", clips));
    }
}
