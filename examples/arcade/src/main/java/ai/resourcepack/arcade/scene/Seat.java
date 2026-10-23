package ai.resourcepack.arcade.scene;

import java.util.UUID;

/** A player sitting at a scene. [index] is their place, 0 for the first to sit, the lowest free one after that. */
public record Seat(UUID id, String name, int index) {}
