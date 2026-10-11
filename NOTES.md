# UI Framework: notes for whoever works on it

The framework under [UI Engine](https://github.com/resourcepack-ai/ui-engine): pictures on walls of maps and in
seated fullscreen views. `README.md` is the
user-facing description; this file is the part that bites.

## Layout

| Path | What |
|---|---|
| `api/` | The public surface: `UIFramework`, `Canvas`, `WallContent`/`WallScreen`, `ViewContent`/`View`, `Pointer`, `Click`, `Key` |
| `render/Palette` | The map colour table, and the HI (YCbCr in 240 colours) and PLAIN encodings |
| `pack/` | `Shaders` (both GLSL dialects), `SymbolHash` (the shader's colour lookup), `ResourcePack` (build, serve, who has it) |
| `net/` | `Nms` (every server internal, by reflection), `MapSender` (diffed map patches, per-player budget), `Pipeline` (ping answers, the hidden inventory) |
| `world/` | `MapWall` (the frames), `Walls` (placed walls and crosshair input), `Views` (seating, camera, pointer, input), `PrivateView` (fake air for players without the pack) |

## Things that are not obvious

- **The server internals are reached by reflection**, so the plugin compiles against the plain 1.21.8 API and needs
  no paperweight. Every member is looked up once in `Nms`'s constructor; a
  missing one disables the plugin at startup with the reason. They were checked
  against Paper 26.2 and 26.3, which agree on all of them.
- **26.3 rewrote the shader dialect**: `layout(location = N)` on every varying,
  `#include` for `#moj_import`, both uniform blocks reordered, and order-
  independent transparency in `text.fsh`. `Shaders` is written once in the 26.3
  form and `to262` derives the old one; the pack ships 26.3 at its root and
  26.2 in the overlay `uiframework_26_2` (formats 88 to 96). An integer
  `max_format` of 97 covers 97.1 (checked against 26.3's own codec). A third
  dialect means a third overlay and a look at `include/globals.glsl` and
  `dynamictransforms.glsl` for that version: std140 member order IS the layout.
- **The pack replaces `text.fsh`/`text.vsh` and `position_tex_color`**. So do
  other plugins that draw effects through the text shader. Two
  packs that both replace `text.fsh` on one client cannot both work: whichever
  is applied last wins and the other's feature silently draws nothing. Nothing
  composes them yet.
- **"Seated" is a camera position, not a uniform.** The view's camera sits at
  k/16 + 3/128 of a block along the picture's normal and on a 0/0.5 grid on the
  other axes; the shaders read that back from `Globals.CameraOffset`. That flag
  is what hides the HUD and draws the view in front of the world, for that
  player only.
- **A view fills the window because `text.vsh` puts it there.** Its maps carry
  the view's columns and rows in the two pixels right of the marker, and the
  pointer is text whose red is `Shaders.POINTER_RED` with the columns and rows
  in green and blue. While seated, each of their vertices goes to its distance
  from the camera's axis, in blocks, over half the picture's size: the window
  edge to edge, at any FOV or window shape. So the wall is placed to fill only
  60% of the player's view (`Views.STRETCHED_FIT`): a frame the client thinks
  is off screen is culled before any shader runs. The pointer sits ON the
  picture's plane (the remap reads its distance from the axis there) and wins
  the depth test by a larger factor than the picture.
- **The mouse skips the tick.** `Pipeline.watch` reads a seated player's
  rotation packets on the netty thread as they arrive, and the pointer's move
  is written straight back (`Pipeline.sendNow`); the server's own position
  updates for the pointer are dropped for that player, or one worked out a
  moment earlier would pull it back. So the pointer is spawned for them already
  in place (`Views.reveal`). The client sends its rotation once per client tick
  (20 a second, whatever the server's tick rate): raising the server's TPS
  would not make it send more often.
- **Depth direction is detected, not assumed.** The in-front trick scales
  `gl_FragCoord.z` towards "near"; the first port assumed reversed depth (26.2). The
  vertex shader passes `ProjMat[2][2] > -0.5` as `mcbReversed`, so the trick
  holds either way. A shader that writes `gl_FragDepth` anywhere writes it on
  every path, or the driver may drop the whole shader.
- **Map ids**: views share `[map-id-base, +1024)` (each player only ever sees
  their own view's frames), walls count up from there, fresh each start. They
  have no saved data, so vanilla never sends anything for them.
- **The mannequin camera** (1.21.9+) has a player's eye height. An armour stand
  (the fallback) makes the client glide its eye height for most of a second,
  off the flagged position the whole time.
- **Paper resends the held item after every cancelled entity click**, which is
  every right-click while seated, and scrolling then pops its name over the
  view. `Pipeline.conceal` rewrites inventory packets to empty for a seated
  player; the server keeps the real items.
- **Permissions are one node per subcommand** (`uiframework.<sub>`, plus
  `.others` when the command names another player), checked in
  `UIFrameworkCommand` through `permissionFor`. `uiframework.admin` is a parent
  in `plugin.yml` that grants them all and is never checked itself. A new
  subcommand needs its node in `plugin.yml`, under the parent too, and in
  `SUBCOMMANDS` so the check and tab completion see it.

## Testing

`/uif test wall` and `/uif test view` need nothing else installed.
`/uif debug <player> point|click|scroll|close` drives a view from the
console, which is how a harness tests one without anybody at the mouse. The
shaders can only be judged in the real client.
