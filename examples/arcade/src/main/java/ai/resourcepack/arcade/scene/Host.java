package ai.resourcepack.arcade.scene;

import java.util.List;

/** What the arcade does for a scene. Call it only from the scene's own methods. */
public interface Host {
    /** The canvas size in pixels. */
    int width();

    int height();

    /** Who is sitting at it now, in seat order. */
    List<Seat> seats();

    /**
     * Plays one of the game's sounds to the players, and round a cabinet to everybody near it.
     * [sound] is a Minecraft sound id, such as "block.note_block.bit" or "entity.experience_orb.pickup".
     */
    void sound(String sound, float volume, float pitch);

    /** Puts a finished game's score in this scene's high score table: its place (1 is the top), or 0. */
    int score(Seat seat, int score);

    /** This scene's high scores, best first. */
    List<Score> scores();

    record Score(String name, int score) {}
}
