# Arcade

An example plugin built on UI Engine's framework API: arcade games in vanilla Minecraft,
drawn into a `Canvas` up to 30 times a second and played with the real mouse and
keyboard.

| Game | Id | |
|---|---|---|
| Neon Breakout | `breakout` | Steer the paddle with the mouse, click (or jump) to launch. |
| Pong Duel | `pong` | Two players, mouse-driven paddles; the computer takes an empty side. |
| Light Cycles | `tron` | Up to four riders on WASD; bots fill the empty seats. |
| Snake Pit | `snake` | Up to four snakes on a grass field, with apples and golden apples. |
| Ore 2048 | `ores2048` | Slide ores together, coal up to nether star. |
| Creeper Sweeper | `sweeper` | Minesweeper with creepers, which do go off. |
| Dungeon Crawl | `dungeon` | A first-person dungeon: WASD to walk, the mouse to turn, click to swing. |
| Note Block Studio | `sequencer` | A 16-step sequencer playing real note block sounds, for up to four. |
| Pixel Wall | `paint` | A shared pixel canvas in the sixteen dye colours, for up to eight. |
| Phantom Field | `asteroids` | Asteroids in glowing vector lines, with phantoms. |
| Biome Racer | `racer` | An old-arcade road racer through blocky biomes. |
| Elytra Glide | `flappy` | One button: boost through gaps in block pillars with fireworks. |
| Block Drop | `blocks` | Falling blocks made of Minecraft blocks, with hold and a next queue. |

- `/arcade play [game]` sits you at a game of your own, fullscreen (Breakout if
  you name none). Sneak to get up.
- `/arcade cabinet [game] [cols] [rows]` puts a cabinet on the wall you look at
  (the block you look at is its top-left corner; 12x7 by default). While nobody
  plays it plays itself, and with nobody near enough to see it, it pauses and
  costs nothing. Click it to sit down at the same game, and everybody
  standing round watches you play on the wall, with the sound coming from the
  cabinet.
- `/arcade remove` takes down the cabinet you look at; `/arcade scores [game]`
  lists the high scores (kept in `scores.yml`).
- From the console or a script, give the place outright:
  `/arcade cabinet <game> <cols> <rows> <x> <y> <z> <facing> [world]` (the
  top-left block, and the way the screen faces), and
  `/arcade remove <x> <y> <z> [world]` (any block the cabinet covers).

Build with `../../gradlew build` from this directory; it builds UI Engine from
the checkout two directories up. Install `build/libs/Arcade-*.jar` beside
UI Engine's jar.

## Writing a game

Every game but Breakout is one class implementing `scene.Scene`, in `games/`, and
one line in `Games`. A scene is plain Java2D: it is told who sat down and got up
(`joined`, `left`), where each player's pointer is, their clicks, scroll wheel and
keys, and is asked to `tick` and `draw` a frame. It never sees Bukkit: sounds and
high scores go through `Host`, so a scene can be run and tested with no server.
With nobody seated a scene must play itself, because a cabinet is seen by
everybody walking past.

## What Breakout shows


- **A game loop off the server thread.** `Loop` runs every game on a thread of
  its own. A canvas may be drawn from any one thread, and the framework sends a
  drawing as soon as it is published, so the game runs at 30 frames a second
  while the server ticks at 20 and the server thread never draws anything. With
  no games the loop does not run at all.
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
