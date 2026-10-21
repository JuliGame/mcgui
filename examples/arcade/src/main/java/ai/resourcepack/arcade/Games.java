package ai.resourcepack.arcade;

import ai.resourcepack.arcade.scene.Scene;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Every scene the arcade can run, by the name players type: /arcade play <name>. */
final class Games {
    static final Map<String, Supplier<Scene>> ALL = new LinkedHashMap<>();

    static {
    }

    private Games() {}
}
