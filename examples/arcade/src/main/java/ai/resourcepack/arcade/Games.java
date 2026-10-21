package ai.resourcepack.arcade;

import ai.resourcepack.arcade.scene.Scene;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Every scene the arcade can run, by the name players type: /arcade play <name>. */
final class Games {
    static final Map<String, Supplier<Scene>> ALL = new LinkedHashMap<>();

    static {
        ALL.put("pong", ai.resourcepack.arcade.games.PongDuel::new);
        ALL.put("tron", ai.resourcepack.arcade.games.LightCycles::new);
        ALL.put("snake", ai.resourcepack.arcade.games.SnakePit::new);
        ALL.put("ores2048", ai.resourcepack.arcade.games.Ore2048::new);
        ALL.put("sweeper", ai.resourcepack.arcade.games.CreeperSweeper::new);
        ALL.put("dungeon", ai.resourcepack.arcade.games.DungeonCrawl::new);
        ALL.put("sequencer", ai.resourcepack.arcade.games.NoteBlockStudio::new);
        ALL.put("paint", ai.resourcepack.arcade.games.PixelWall::new);
        ALL.put("asteroids", ai.resourcepack.arcade.games.PhantomField::new);
        ALL.put("racer", ai.resourcepack.arcade.games.BiomeRacer::new);
        ALL.put("flappy", ai.resourcepack.arcade.games.ElytraGlide::new);
        ALL.put("blocks", ai.resourcepack.arcade.games.BlockDrop::new);
    }

    private Games() {}
}
