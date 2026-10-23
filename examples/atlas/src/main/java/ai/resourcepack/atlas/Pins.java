package ai.resourcepack.atlas;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Places players have pinned on the map, shared by every map; kept in pins.yml. Read from any thread. */
final class Pins {
    record Pin(UUID owner, String name, UUID world, int x, int z) {}

    /** The game's sixteen dye colours: each player's pins are one of them. */
    static final int[] DYES = {0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52,
            0x9D9D97, 0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21};

    static final int PER_PLAYER = 5;

    final List<Pin> all = new CopyOnWriteArrayList<>();
    private final File file;

    Pins(File file) {
        this.file = file;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : yaml.getMapList("pins")) {
            try {
                all.add(new Pin(UUID.fromString((String) m.get("owner")), (String) m.get("name"), UUID.fromString((String) m.get("world")),
                        ((Number) m.get("x")).intValue(), ((Number) m.get("z")).intValue()));
            } catch (RuntimeException ignored) {
                // A hand-edited entry that does not parse is skipped.
            }
        }
    }

    static int colour(UUID owner) {
        return DYES[Math.floorMod(owner.hashCode(), DYES.length)];
    }

    /** Pins [p], dropping its owner's oldest beyond {@link #PER_PLAYER}. */
    void add(Pin p) {
        all.add(p);
        List<Pin> mine = all.stream().filter(q -> q.owner().equals(p.owner())).toList();
        for (int i = 0; i < mine.size() - PER_PLAYER; i++) all.remove(mine.get(i));
    }

    void save() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Pin p : all) {
            out.add(Map.of("owner", p.owner().toString(), "name", p.name(), "world", p.world().toString(), "x", p.x(), "z", p.z()));
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("pins", out);
        yaml.save(file);
    }
}
