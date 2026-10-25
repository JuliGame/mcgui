package ai.resourcepack.ui.framework.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The game's names as this server runs them. The code asks for Mojang's: Paper (1.20.5 and newer) and Spigot 26.1
 * and newer run under them, so there they are the names. Spigot before 26.1 runs Spigot's class names and Mojang's
 * obfuscated member names, so there a table made from both mappings for the running version
 * ({@code spigot-names/<version>.txt}, written by {@code tools/SpigotNames.java}) turns one into the other.
 */
public final class Names {
    private Names() {}

    /** Null where the server runs Mojang's names. */
    private static volatile Table table;

    private record Table(Map<String, String> runtime, Map<String, String> mojang, Map<String, String> members) {}

    /**
     * Reads this server's names, once, before anything is looked up: [version] is the Minecraft version
     * ({@code 1.21.11}). Throws where the server runs neither Mojang's names nor a version with a table.
     */
    public static void init(String version) throws ReflectiveOperationException {
        try {
            Class.forName("net.minecraft.server.level.ServerPlayer");
            table = null;
            return;
        } catch (ClassNotFoundException spigot) {
            // Spigot's names: below.
        }
        String resource = "/spigot-names/" + version + ".txt";
        try (InputStream in = Names.class.getResourceAsStream(resource)) {
            if (in == null) throw new ClassNotFoundException("MC GUI has no names for Spigot " + version + " (it knows 1.21.9 to 1.21.11, and 26.1 on)");
            Map<String, String> runtime = new HashMap<>(), mojang = new HashMap<>(), members = new HashMap<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\t", -1);
                switch (p[0]) {
                    case "C" -> {
                        runtime.put(p[1], p[2]);
                        mojang.put(p[2], p[1]);
                    }
                    case "F" -> members.put(p[1] + " " + p[2], p[3]);
                    case "M" -> members.put(p[1] + " " + p[2] + "(" + p[3] + ")", p[4]);
                    default -> { }
                }
            }
            table = new Table(runtime, mojang, members);
        } catch (IOException e) {
            throw new ClassNotFoundException("Cannot read " + resource, e);
        }
    }

    /** Whether names are being translated (Spigot before 26.1). */
    public static boolean translated() {
        return table != null;
    }

    /** The class Mojang calls [name]. */
    public static Class<?> cls(String name) throws ClassNotFoundException {
        Table t = table;
        return Class.forName(t == null ? name : t.runtime.getOrDefault(name, name));
    }

    /** What Mojang calls the class [c]. */
    static String mojang(Class<?> c) {
        Table t = table;
        if (c.isArray()) return mojang(c.getComponentType()) + "[]";
        String name = c.getName();
        return t == null ? name : t.mojang.getOrDefault(name, name);
    }

    /** The name [declaring]'s field Mojang calls [name] has here. */
    static String field(Class<?> declaring, String name) {
        Table t = table;
        if (t == null) return name;
        return t.members.getOrDefault(mojang(declaring) + " " + name, name);
    }

    /** The name [declaring]'s method Mojang calls [name], taking [parameters], has here. */
    static String method(Class<?> declaring, String name, Class<?>... parameters) {
        Table t = table;
        if (t == null) return name;
        StringBuilder key = new StringBuilder(mojang(declaring)).append(' ').append(name).append('(');
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) key.append(',');
            key.append(mojang(parameters[i]));
        }
        return t.members.getOrDefault(key.append(')').toString(), name);
    }
}
