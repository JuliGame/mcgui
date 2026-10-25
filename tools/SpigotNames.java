import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * java SpigotNames.java <maps-mojang.txt> <maps-spigot.csrg> <classes.txt> <out.txt>
 *
 * The table MC GUI reads on a Spigot server older than 26.1, which runs the game under Spigot's class names and
 * Mojang's obfuscated member names, where the code asks for Mojang's names. From BuildTools' two mapping files
 * for one Minecraft version (in ~/.m2/repository/org/spigotmc/minecraft-server/<version>-R0.1-SNAPSHOT/ after
 * {@code BuildTools --rev <version> --remapped}):
 *
 * <pre>
 * C  mojang.Class        runtime.Class              each class below, and each one a parameter of theirs is
 * F  mojang.Class  name  obf                        the fields of each class in [classes.txt]
 * M  mojang.Class  name  param,types  obf           and its methods, parameters in Mojang's names
 * </pre>
 *
 * Only the members of the classes MC GUI looks into are kept, which keeps the table small and is all the
 * mappings' licence allows anyway.
 */
public class SpigotNames {
    /** Spigot's name for obfuscated class [obf]: its own, or (a nested class Spigot leaves alone) its outer class's with its own end. */
    static String spigotName(Map<String, String> spigot, String obf) {
        String own = spigot.get(obf);
        if (own != null) return own;
        int nest = obf.lastIndexOf('$');
        return nest < 0 ? obf : spigotName(spigot, obf.substring(0, nest)) + obf.substring(nest);
    }

    public static void main(String[] a) throws IOException {
        Path mojang = Path.of(a[0]), spigot = Path.of(a[1]), list = Path.of(a[2]), out = Path.of(a[3]);
        Map<String, String> spigotNames = new HashMap<>();
        for (String line : Files.readAllLines(spigot)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.trim().split(" ");
            if (p.length == 2) spigotNames.put(p[0].replace('/', '.'), p[1].replace('/', '.'));
        }
        Set<String> wanted = new HashSet<>();
        for (String line : Files.readAllLines(list)) {
            line = line.strip();
            if (!line.isEmpty() && !line.startsWith("#")) wanted.add(line);
        }

        Map<String, String> runtime = new LinkedHashMap<>();
        List<String> members = new ArrayList<>();
        Set<String> named = new TreeSet<>(wanted);
        Set<String> found = new HashSet<>();
        String owner = null;
        try (BufferedReader r = Files.newBufferedReader(mojang)) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#") || line.trim().startsWith("#")) continue;
                if (!line.startsWith(" ")) {
                    // net.minecraft.Foo -> abc:
                    String[] p = line.substring(0, line.length() - 1).split(" -> ");
                    String obf = p[1];
                    owner = p[0];
                    runtime.put(owner, spigotName(spigotNames, obf));
                    if (wanted.contains(owner)) found.add(owner);
                    continue;
                }
                if (owner == null || !wanted.contains(owner)) continue;
                String m = line.trim();
                String[] p = m.split(" -> ");
                String obf = p[1], left = p[0];
                int paren = left.indexOf('(');
                if (paren < 0) {
                    // type name -> obf
                    String name = left.substring(left.lastIndexOf(' ') + 1);
                    members.add("F\t" + owner + "\t" + name + "\t" + obf);
                } else {
                    // [line:line:]type name(params)[:line:line] -> obf
                    String head = left.substring(0, paren);
                    String name = head.substring(head.lastIndexOf(' ') + 1);
                    if (name.equals("<init>") || name.equals("<clinit>")) continue;
                    String params = left.substring(paren + 1, left.indexOf(')', paren));
                    members.add("M\t" + owner + "\t" + name + "\t" + params + "\t" + obf);
                    for (String t : params.split(",")) if (!t.isEmpty()) named.add(t.replace("[]", ""));
                }
            }
        }
        Set<String> missing = new TreeSet<>(wanted);
        missing.removeAll(found);
        // A class a newer version has and this one lacks (EntityTypes is 26.x's): the code looks for it leniently.
        if (!missing.isEmpty()) System.err.println("Not in this version (left out): " + missing);
        Files.createDirectories(out.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write("# MC GUI's names for a Spigot server older than 26.1. Made by tools/SpigotNames.java, from Mojang's\n");
            w.write("# mappings (subject to their licence: a part of them, for the members MC GUI reaches) and Spigot's.\n");
            int classes = 0;
            for (String c : named) {
                String to = runtime.get(c);
                if (to == null) continue; // a Java or library type, named the same everywhere
                w.write("C\t" + c + "\t" + to + "\n");
                classes++;
            }
            for (String s : members) w.write(s + "\n");
            System.out.println(out + ": " + classes + " classes, " + members.size() + " members of " + found.size());
        }
    }
}
