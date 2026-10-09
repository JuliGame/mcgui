package ai.resourcepack.ui.html.css;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** An {@code @font-face}: a family name, where its files are, and which weight and style they are. */
public record FontFace(String family, List<String> sources, int weight, boolean italic) {
    static FontFace from(List<Declaration> decls) {
        String family = null;
        List<String> sources = new ArrayList<>();
        int weight = 400;
        boolean italic = false;
        for (Declaration d : decls) {
            switch (d.property()) {
                case "font-family" -> family = CssParser.unquote(d.value().trim());
                case "src" -> {
                    for (String part : Values.splitCommas(d.value())) {
                        int at = part.indexOf("url(");
                        if (at < 0) continue;
                        int close = part.indexOf(')', at);
                        if (close < 0) continue;
                        sources.add(CssParser.unquote(part.substring(at + 4, close).trim()));
                    }
                }
                case "font-weight" -> {
                    String v = d.value().trim().toLowerCase(Locale.ROOT);
                    // A range ("100 900", a variable font) is read as its start.
                    String first = v.split("\\s+")[0];
                    weight = switch (first) {
                        case "normal" -> 400;
                        case "bold" -> 700;
                        default -> {
                            try {
                                yield (int) Double.parseDouble(first);
                            } catch (NumberFormatException e) {
                                yield 400;
                            }
                        }
                    };
                }
                case "font-style" -> italic = d.value().toLowerCase(Locale.ROOT).contains("italic") || d.value().toLowerCase(Locale.ROOT).contains("oblique");
                default -> {}
            }
        }
        return family == null || sources.isEmpty() ? null : new FontFace(family, sources, weight, italic);
    }
}
