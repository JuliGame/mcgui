package ai.resourcepack.ui.html.layout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CssStringTest {
    @Test
    void escapesThatAreNoCharacterBecomeTheReplacementCharacter() {
        assertEquals("\uFFFD", BoxBuilder.cssString("'\\FFFFFF'"));
        assertEquals("\uFFFD", BoxBuilder.cssString("'\\110000'"));
        assertEquals("a\uFFFDb", BoxBuilder.cssString("'a\\0 b'"));
        assertEquals("\uFFFD", BoxBuilder.cssString("'\\D800'"));
    }

    @Test
    void escapesOfRealCharactersAreKept() {
        assertEquals("\u201Cx\u201D", BoxBuilder.cssString("\"\\201Cx\\201D\""));
        assertEquals("\uD83D\uDE00x", BoxBuilder.cssString("'\\1F600 x'"));
        assertEquals("\uDBFF\uDFFF", BoxBuilder.cssString("'\\10FFFF'"));
        assertEquals("'", BoxBuilder.cssString("'\\''"));
    }
}
