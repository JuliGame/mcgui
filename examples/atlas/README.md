# Atlas

An example plugin built on MC GUI's framework API: the game's own map, but live, as big as
your screen, and something you can use.

- `/atlas` opens it fullscreen, centred on you and following you. WASD moves it, the
  wheel zooms (1:4 out to 8:1 in), hovering a place shows what is there (block, height,
  water depth, biome) in the game's tooltip, a click pins it for everybody (click your
  own pin to take it down), a right-click takes you there (`atlas.teleport`), F comes
  back to you, sneaking gets up.
- `/atlas wall [cols] [rows]` puts a map on the wall you look at, centred where you
  stand. Everybody sees players move on it live; clicking it opens the map fullscreen at
  that spot, scrolling it zooms it.
- `/atlas remove` takes the wall you look at down; `/atlas pins clear` removes every pin.

It looks like a filled map because it is drawn the way the game draws one: each column's
map colour from the server (`BlockData.getMapColor()`), shaded lighter or darker by the
height of the block to its north, water darker the deeper it is, on parchment. Like a real
map it only knows what has been seen: it never loads a chunk, so the world fills in as
players move about.

Build with `../../gradlew build` from this directory. The font is
[Monocraft](https://github.com/IdreesInc/Monocraft) (SIL Open Font License, `fonts/`).

## What it shows

- **Drawing off the server thread.** The server thread only snapshots a few loaded
  chunks a tick and notes where players are; colours, shading and the picture are worked
  out on the map's own thread, ten frames a second.
- **Nothing sent when nothing changed.** A map is drawn again only when something it shows
  moved, and the canvas sends only the tiles that differ, so a still map costs nothing.
- **A view and a wall from one class.** `AtlasMap` draws into a `Canvas`; a `ViewContent`
  drives it with the pointer, wheel, keys and clicks, a `WallContent` shows it to a room.
