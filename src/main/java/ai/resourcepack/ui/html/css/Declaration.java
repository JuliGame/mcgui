package ai.resourcepack.ui.html.css;

/** One {@code property: value} pair, value as written (urls already made absolute). */
public record Declaration(String property, String value, boolean important) {}
