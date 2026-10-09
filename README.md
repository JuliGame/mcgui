# Screen Engine

Full-colour pictures in vanilla Minecraft, on walls of maps and in fullscreen
views a player sits at and drives with their mouse. No mods: a Paper plugin and
a small resource pack it builds and serves itself.

It is a framework. On its own it shows a test picture (`/screens test`); other
plugins draw into it. UI Engine, which shows HTML pages, is the first.

**Needs** Paper 26.2 or 26.3 (Java 25). Build with
`./gradlew build --offline` (JDK 17 or 21 to run Gradle).

## What it does

- **Walls.** A wall of invisible glow item frames holding maps nobody else owns.
  Each player is sent only the rectangle of each map that changed since what
  their client last got, within a per-player byte budget. Players can point at a
  wall with their crosshair, click it and scroll it.
- **Views.** A player sits: their camera locks onto a picture only they can
  see, their mouse moves a pointer across it, the attack and use keys click, the
  wheel scrolls, movement keys are keys and sneaking gets up. They are put back
  exactly where they stood.
- **Colour.** With the pack, maps carry YCbCr 4:2:0 in 240 map colours and the
  pack's text shader decodes them, smoothly scaled, unlit and unfogged. Without
  it, players get dithered vanilla map colours.

## Commands

| Command | Does |
|---|---|
| `/screens test wall [cols] [rows] [player]` | Puts a test picture on the wall you look at |
| `/screens test view [cols] [rows] [player]` | Opens a test picture as a fullscreen view |
| `/screens fov <degrees>` | Fits views to your FOV setting (the server cannot read it) |
| `/screens pack` · `stats` · `clear` | Re-send the pack · bytes sent · remove test walls |
| `/screens debug <player> point <x> <y>` · `click` · `scroll <n>` · `close` | Drive a player's view from the console |

`/screens` needs `screenengine.admin` (op), except `fov` and `pack`.

## API

Depend on `ScreenEngine` in your `plugin.yml`, compile against this jar, then:

```java
ScreenEngine engine = ScreenEngine.get();
Canvas canvas = engine.canvas(4, 3);               // 512 x 384 pixels
canvas.draw(image, 0, 0);                          // only changed tiles are re-sent
engine.placeWall(topLeft, BlockFace.SOUTH, 4, 3, viewer -> canvas);
engine.openView(player, () -> canvas, ViewOptions.DEFAULT);
```

`WallContent` and `ViewContent` have default methods for looking, pointing,
clicking, scrolling, keys and chat; implement the ones you need. Every callback
runs on the server thread. A wall can show each viewer their own canvas.
