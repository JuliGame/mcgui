package ai.resourcepack.screens.api;

/**
 * The keys a seated player can press, as Minecraft reports them: movement, jump, sprint, and the
 * swap-hands key (F by default). Sneaking always closes the view, so it is never a key.
 */
public enum Key { UP, DOWN, LEFT, RIGHT, JUMP, SPRINT, SWAP_HANDS }
