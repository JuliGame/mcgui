package ai.resourcepack.screens.api;

/** Why a view closed. */
public enum CloseReason {
    /** The player sneaked to get up. */
    PLAYER,
    /** Something called {@link View#close()}, or another view replaced this one. */
    CLOSED,
    /** The player quit, died, changed world, was teleported away or was hurt. */
    INTERRUPTED,
    /** The engine is shutting down. */
    SHUTDOWN
}
