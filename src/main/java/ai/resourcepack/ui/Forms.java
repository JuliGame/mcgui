package ai.resourcepack.ui;

import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Forms without typing: what a page's fields hold, put into its commands as {@code {form.<name>}}.
 * Checkboxes and radios tick (as before), a {@code <select>} steps to its next option on a click, a range
 * slider jumps to where it was clicked, and a number field goes down a step on its left half and up a step on
 * its right. A form's {@code data-command}, {@code data-console} and {@code data-message} run when it is sent,
 * by a submit button inside it; any other element's run on a click and read the fields of the form it is in,
 * or of the whole page.
 */
final class Forms {
    private static final Pattern FIELD = Pattern.compile("\\{form\\.([A-Za-z0-9_.-]+)}");

    private Forms() {}

    /**
     * Every field's value in the form [from] is, or is in (else the whole page), by name; several fields of one
     * name (ticked checkboxes) are joined with commas. [submitter], the button that sent it, adds its own
     * name and value, as a browser does.
     */
    static Map<String, String> values(Element from, Element submitter) {
        Element form = from.normalName().equals("form") ? from : from.closest("form");
        Element scope = form != null ? form : from.ownerDocument();
        Map<String, String> out = new LinkedHashMap<>();
        if (scope == null) return out;
        for (Element field : scope.select("input[name], select[name], textarea[name], button[name]")) {
            String name = field.attr("name");
            String value = switch (field.normalName()) {
                case "select" -> {
                    Element chosen = chosen(field);
                    yield chosen == null ? null : chosen.hasAttr("value") ? chosen.attr("value") : chosen.text();
                }
                case "textarea" -> field.wholeText();
                case "button" -> field == submitter ? field.attr("value") : null;
                default -> switch (type(field)) {
                    case "checkbox", "radio" -> field.hasAttr("checked") ? (field.hasAttr("value") ? field.attr("value") : "on") : null;
                    case "submit", "image" -> field == submitter ? field.attr("value") : null;
                    case "reset", "button", "file", "password" -> null;
                    case "range" -> rangeValue(field).toPlainString();
                    default -> field.attr("value");
                };
            };
            if (value == null) continue;
            out.merge(name, value, (a, b) -> a + "," + b);
        }
        return out;
    }

    /** [template] with each {@code {form.<name>}} filled from [values]; a field nobody filled is empty. */
    static String fill(String template, Map<String, String> values) {
        if (template.indexOf("{form.") < 0) return template;
        Matcher m = FIELD.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(values.getOrDefault(m.group(1), "")));
        m.appendTail(out);
        return out.toString();
    }

    /** The form [e] sends when clicked (a submit or reset button inside one that has something to run), or null. */
    static Element submitted(Element e) {
        boolean button = switch (e.normalName()) {
            // A button in a form submits unless it says otherwise.
            case "button" -> !type(e).equals("button");
            case "input" -> type(e).equals("submit") || type(e).equals("image") || type(e).equals("reset");
            default -> false;
        };
        if (!button) return null;
        Element form = e.closest("form");
        if (form == null) return null;
        return isReset(e) || form.hasAttr("data-command") || form.hasAttr("data-console") || form.hasAttr("data-message") ? form : null;
    }

    static boolean isReset(Element e) {
        return type(e).equals("reset");
    }

    static boolean isSelect(Element e) {
        return e.normalName().equals("select") && !e.hasAttr("disabled");
    }

    /** A range slider or a number field: what a click on it sets depends on where it lands. */
    static boolean isSlider(Element e) {
        return e.normalName().equals("input") && (type(e).equals("range") || type(e).equals("number")) && !e.hasAttr("disabled");
    }

    /** Selects the option after the chosen one in [select], back to the first after the last. */
    static void next(Element select) {
        Elements options = select.select("option:not([disabled])");
        if (options.isEmpty()) return;
        int at = options.indexOf(chosen(select));
        for (Element o : select.select("option")) o.removeAttr("selected");
        options.get((at + 1) % options.size()).attr("selected", "");
    }

    /**
     * A click [t] of the way across [input] (0 at its left edge, 1 at its right): a range takes the value
     * there, to its step; a number field goes down a step on its left half and up one on its right.
     */
    static void slide(Element input, double t) {
        BigDecimal min = number(input.attr("min"), type(input).equals("range") ? BigDecimal.ZERO : null);
        BigDecimal max = number(input.attr("max"), type(input).equals("range") ? BigDecimal.valueOf(100) : null);
        BigDecimal step = number(input.attr("step"), BigDecimal.ONE);
        if (step.signum() <= 0) step = BigDecimal.ONE;
        BigDecimal value;
        if (type(input).equals("range")) {
            if (min == null || max == null || max.compareTo(min) <= 0) return;
            double raw = min.doubleValue() + Math.max(0, Math.min(1, t)) * (max.doubleValue() - min.doubleValue());
            BigDecimal steps = BigDecimal.valueOf((raw - min.doubleValue()) / step.doubleValue()).setScale(0, java.math.RoundingMode.HALF_UP);
            value = min.add(step.multiply(steps));
        } else {
            BigDecimal now = number(input.attr("value"), min != null ? min : BigDecimal.ZERO);
            value = t < 0.5 ? now.subtract(step) : now.add(step);
        }
        if (min != null && value.compareTo(min) < 0) value = min;
        if (max != null && value.compareTo(max) > 0) value = max;
        input.attr("value", value.stripTrailingZeros().toPlainString());
    }

    /** Puts every field of [form] back as [original] (the same form in the page as loaded) has it. */
    static void reset(Element form, Element original) {
        Elements now = form.select("input, select, textarea, option"), was = original.select("input, select, textarea, option");
        if (now.size() != was.size()) return;
        for (int i = 0; i < now.size(); i++) {
            for (String attr : new String[]{"checked", "selected", "value"}) {
                if (was.get(i).hasAttr(attr)) now.get(i).attr(attr, was.get(i).attr(attr));
                else now.get(i).removeAttr(attr);
            }
        }
    }

    private static Element chosen(Element select) {
        Element chosen = select.selectFirst("option[selected]");
        return chosen != null ? chosen : select.selectFirst("option");
    }

    /** A range's value: its value attribute, else halfway, as a browser starts it. */
    private static BigDecimal rangeValue(Element range) {
        BigDecimal min = number(range.attr("min"), BigDecimal.ZERO), max = number(range.attr("max"), BigDecimal.valueOf(100));
        return number(range.attr("value"), min.add(max).divide(BigDecimal.valueOf(2))).stripTrailingZeros();
    }

    private static String type(Element e) {
        String t = e.attr("type").toLowerCase(Locale.ROOT);
        if (e.normalName().equals("button")) return t.isEmpty() ? "submit" : t;
        return t.isEmpty() ? "text" : t;
    }

    private static BigDecimal number(String s, BigDecimal otherwise) {
        try {
            return s == null || s.isBlank() ? otherwise : new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }
}
