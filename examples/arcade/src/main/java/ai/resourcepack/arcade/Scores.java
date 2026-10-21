package ai.resourcepack.arcade;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** The ten best scores, kept in scores.yml. Read from the loop thread and the server thread alike. */
final class Scores {
    record Entry(String name, int score) {}

    private static final int KEEP = 10;

    private final File file;
    private final List<Entry> list = new ArrayList<>();
    private volatile List<Entry> view = List.of();

    Scores(File file) {
        this.file = file;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : yaml.getMapList("scores")) {
            if (m.get("name") instanceof String n && m.get("score") instanceof Number s) list.add(new Entry(n, s.intValue()));
        }
        sort();
    }

    /** The table, best first. Never changed once returned. */
    List<Entry> top() {
        return view;
    }

    /** Adds [score]: its place in the table (1 is the top), or 0 when it did not make it. */
    synchronized int submit(String name, int score) {
        if (score <= 0) return 0;
        Entry e = new Entry(name, score);
        list.add(e);
        sort();
        return list.indexOf(e) + 1;
    }

    private void sort() {
        list.sort(Comparator.comparingInt(Entry::score).reversed());
        while (list.size() > KEEP) list.remove(list.size() - 1);
        view = List.copyOf(list);
    }

    void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Entry e : view) out.add(Map.of("name", e.name(), "score", e.score()));
        yaml.set("scores", out);
        yaml.save(file);
    }
}
