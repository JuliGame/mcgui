# UI Framework

Full-colour pictures in vanilla Minecraft, on walls of maps and in fullscreen
views a player sits at and drives with their mouse. No mods: a Paper plugin and
a small resource pack it builds and serves itself.

It is a framework. On its own it shows a test picture (`/uif test`); other
plugins draw into it. UI Engine, which shows HTML pages, is the first.

**Needs** Paper 26.2 or 26.3 (Java 25). Build with
`./gradlew build` (JDK 17 or 21 to run Gradle).

## What it does

- **Walls.** A wall of invisible glow item frames holding maps nobody else owns.
  Each player is sent only the rectangle of each map that changed since what
  their client last got, within a per-player byte budget. Players can point at a
  wall with their crosshair, click it and scroll it.
- **Views.** A player sits: their camera locks onto a picture only they can
  see, their mouse moves a pointer across it, the attack and use keys click, the
  wheel scrolls, movement keys are keys and sneaking gets up. They are put back
  exactly where they stood. With the pack the picture fills the whole window,
  whatever its shape and their FOV.
- **Colour.** With the pack, maps carry YCbCr 4:2:0 in 240 map colours and the
  pack's text shader decodes them, smoothly scaled, unlit and unfogged. Without
  it, players get dithered vanilla map colours.

## Commands

| Command | Does |
|---|---|
| `/uif test wall [cols] [rows] [player]` | Puts a test picture on the wall you look at |
| `/uif test view [cols] [rows] [player]` | Opens a test picture as a fullscreen view |
| `/uif fov <degrees>` | Fits views to your FOV setting, for players without the pack (the server cannot read it) |
| `/uif pack` · `stats` · `clear` | Re-send the pack · bytes sent · remove test walls |
| `/uif debug <player> point <x> <y>` · `click` · `scroll <n>` · `close` | Drive a player's view from the console |

### Permissions

Each subcommand has its own node, so a server can hand out one tool without
the rest. Pointing a command at another player needs that command's `.others`
node as well.

| Node | Allows | Default |
|---|---|---|
| `uiframework.test` | `/uif test` for yourself | op |
| `uiframework.test.others` | `/uif test ... <player>` for somebody else | op |
| `uiframework.stats` | `/uif stats` | op |
| `uiframework.clear` | `/uif clear` (removes every test wall, whoever placed it) | op |
| `uiframework.debug` | `/uif debug` on your own view | op |
| `uiframework.debug.others` | `/uif debug` on another player's view | op |
| `uiframework.fov` | `/uif fov` | everyone |
| `uiframework.pack` | `/uif pack` | everyone |
| `uiframework.admin` | all of the above, in one line | op |

`uiframework.admin` is only a parent: the plugin checks the nodes it grants,
never `uiframework.admin` itself. Tab completion offers only the subcommands
you may run.

## API

Depend on `UIFramework` in your `plugin.yml`, compile against this jar, then:

```java
UIFramework engine = UIFramework.get();
Canvas canvas = engine.canvas(4, 3);               // 512 x 384 pixels
canvas.draw(image, 0, 0);                          // only changed tiles are re-sent
other.show(canvas.copy());                         // same picture, same tiles: nothing re-encoded or re-sent
engine.placeWall(topLeft, BlockFace.SOUTH, 4, 3, viewer -> canvas);
engine.openView(player, () -> canvas, ViewOptions.DEFAULT);
```

`WallContent` and `ViewContent` have default methods for looking, pointing,
clicking, scrolling, keys and chat; implement the ones you need. Every callback
runs on the server thread. A wall can show each viewer their own canvas.
