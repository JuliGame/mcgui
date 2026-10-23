package ai.resourcepack.ui.framework.pack;

import ai.resourcepack.ui.framework.render.Palette;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The text shaders' colour lookup gives back every symbol, and what text.vsh hands text.fsh lines up. Whether the
 * shaders draw right can only be judged in the real client (NOTES.md).
 */
class ShadersTest {
    private static final Palette PALETTE = new Palette(c -> 0xFF000000 | (c * 0x9E3779B1));

    @Test
    void everySymbolColourLooksUpToItsSymbol() {
        SymbolHash hash = new SymbolHash(PALETTE.symbolRgb);
        for (int s = 0; s < Palette.SYMBOLS; s++) assertEquals(s, hash.lookup(PALETTE.symbolRgb[s]), "symbol " + s);
        // The shader reads a slot as four bytes of one uint, lowest first.
        int filled = 0;
        for (int w : hash.packed) for (int b = 0; b < 4; b++) if ((w >>> (b * 8) & 255) != 0) filled++;
        assertEquals(Palette.SYMBOLS, filled);
    }

    @Test
    void shadersCarryTheTableAndNoPlaceholders() {
        for (Shaders.Dialect d : Shaders.Dialect.values()) {
            String vsh = Shaders.textVsh(d, PALETTE), fsh = Shaders.textFsh(d, PALETTE);
            for (String s : new String[]{vsh, fsh}) {
                assertFalse(s.contains("@"), "a placeholder was left in a " + d + " shader");
                assertTrue(s.contains("const uint MCB_T[" + SymbolHash.SLOTS / 4 + "]"));
            }
        }
    }

    private static final Pattern VARYING = Pattern.compile("layout\\(location = (\\d+)\\) (flat )?(in|out) (\\w+) (\\w+);");

    @Test
    void everyVaryingTextFshReadsTextVshWritesAtTheSameLocation() {
        String vsh = Shaders.textVsh(Shaders.Dialect.V26_3, PALETTE), fsh = Shaders.textFsh(Shaders.Dialect.V26_3, PALETTE);
        Map<String, String> outs = new HashMap<>();
        Matcher m = VARYING.matcher(vsh);
        while (m.find()) if (m.group(3).equals("out")) outs.put(m.group(5), m.group(1) + " " + m.group(2) + m.group(4));
        m = VARYING.matcher(fsh);
        int ins = 0;
        while (m.find()) {
            if (!m.group(3).equals("in")) continue;
            ins++;
            assertEquals(m.group(1) + " " + m.group(2) + m.group(4), outs.get(m.group(5)), m.group(5));
        }
        assertTrue(ins >= 10, "found " + ins + " inputs");
    }
}
