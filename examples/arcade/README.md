# Neon Breakout

An example plugin built on UI Framework's API: a whole arcade game in vanilla
Minecraft, drawn into a `Canvas` 30 times a second.

- `/arcade play` sits you at a game of your own, fullscreen. Move the mouse to
  steer the paddle, click (or jump) to launch, sneak to get up.
- `/arcade cabinet [cols] [rows]` puts a cabinet on the wall you look at (the
  block you look at is its top-left corner; 12x7 by default). While nobody plays
  it plays itself. Click it to sit down at the same game, and everybody standing
  round watches you play on the wall, with the sound coming from the cabinet.
- `/arcade remove` takes down the cabinet you look at; `/arcade scores` lists the
  high scores (kept in `scores.yml`).

Build with `../../gradlew build` from this directory; it builds UI Framework from
the checkout two directories up. Install `build/libs/Arcade-*.jar` beside
UIFramework's jar.

## What it shows

- **A game loop off the server thread.** `Loop` runs every game on a thread of
  its own. A canvas may be drawn from any one thread, and the framework sends a
  drawing as soon as it is published, so the game runs at 30 frames a second
  while the server ticks at 20 and the server thread never draws anything.
- **Drawing only what moved.** The background and the bricks are kept in a still
  picture that changes only when a brick does. Each frame redraws only the
  rectangles something moved through and hands the canvas just those, so only
  the tiles they touch are re-encoded and each viewer gets only the changed part
  of each map.
- **One canvas, many screens.** A cabinet's wall and the seated player's view
  show the same `Canvas`, so each frame is drawn and encoded once for the player
  and every spectator.
- **The pointer as a controller.** `ViewContent.pointer` steers the paddle, with
  the pointer hidden (`Pointer.HIDDEN`); `click` and the jump key launch.
