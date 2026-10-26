package ai.resourcepack.ui.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What Rhino cannot read goes through Babel; what it can, does not; a real syntax error says so. */
class TranspilerTest {
    @Test
    void convertsOnlyWhatRhinoCannotRead() throws Exception {
        String plain = "var a = [1, 2].map(x => x * 2); let b = `${a}`;";
        assertSame(plain, Transpiler.prepare(plain, "plain.js"), "readable as it is: untouched");

        String modern = "class A { #x = 1; get x() { return this.#x; } } const s = [...[1, 2]]; for (const v of s) {} async function f() { await null; }";
        long t0 = System.nanoTime();
        String out = Transpiler.prepare(modern, "modern.js");
        long first = (System.nanoTime() - t0) / 1_000_000;
        assertNotEquals(modern, out);
        assertTrue(!out.contains("class A") && !out.contains("..."), out);
        long t1 = System.nanoTime();
        assertEquals(out, Transpiler.prepare(modern, "modern.js"));
        System.out.println("first conversion " + first + " ms, again " + (System.nanoTime() - t1) / 1_000_000 + " ms");

        Transpiler.Failed bad = assertThrows(Transpiler.Failed.class, () -> Transpiler.prepare("class {", "bad.js"));
        assertTrue(bad.getMessage().contains("bad.js"), bad.getMessage());
    }
}
