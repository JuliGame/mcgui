# MC GUI

Put an HTML page in `plugins/MCGUI/pages/` and it is in the game: on a wall,
or fullscreen in front of the player, who points with their mouse and clicks.
Vanilla clients, no mods: a Paper plugin and a small resource pack it builds and
serves itself. Edit the file and it updates in game within a second.

No browser runs. MC GUI has its own HTML and CSS renderer, so what works is
**static** HTML and CSS: no JavaScript. Links, commands, hover states, scrolling,
sounds and a few form bits are made to work in game instead (below).

Under the pages is a framework any plugin can draw with: full-colour pictures on
walls and in fullscreen views, with the player's mouse, clicks and keys. The
[examples](examples/) include an arcade of games and a live world map built on
it. See [For plugin developers](#for-plugin-developers).

**Needs** Paper 26.2 or 26.3 (Java 25). PlaceholderAPI is optional. Build with
`./gradlew build` (JDK 17 or 21 to run Gradle); the plugin is
`build/libs/MCGUI-<version>.jar`. `./gradlew renderSamples` renders
`samples/*.html` to `build/samples/` to check the renderer by eye.

## How it works

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
- **Sounds.** Every `.ogg` in `plugins/MCGUI/sounds/` goes into the pack
  as a sound named after its path: `sounds/menu/open.ogg` plays as
  `uiengine:menu/open`, from a page, `/playsound` or any plugin. Names are lower
  case. Restart after adding one, so the pack is built again.

A new `pages/` folder starts with examples: `menu`, `rules`, `shop`, and
`market`, a night-market trader's stall that shows off hover tooltips, a
click-to-showcase item stand (radio inputs, no JavaScript), pixel-art item
sprites and pre-rendered 3D models, and a Trade button that runs a console
`give` with item components. Try `/mcgui open market`. `market-overlay` and
`market-blur` are the same stall as an overlay, with the world round it.

## Commands

`/gui` works as well as `/mcgui` unless `gui-alias: false` in `config.yml` (for a
server where another plugin has `/gui`). MC GUI was called UI Engine until 0.1.1:
its folder moves from `plugins/UIEngine` on first start, and a plugin that
depends on `UIEngine` still loads.

| Command | Does |
|---|---|
| `/mcgui open <page> [player]` | Opens a page fullscreen. Sneak to leave |
| `/mcgui close [player]` | Closes it |
| `/mcgui place <page> [cols] [rows] [name] [shared]` | Puts a page on the wall you look at (the block becomes the top-left corner) |
| `/mcgui remove [name]` | Takes a wall screen down (or the one you look at) |
| `/mcgui list` · `/mcgui reload` | Pages and screens · read the config and every page again |
| `/mcgui import <code>` | Writes pages made on [mcgui.dev](https://mcgui.dev) into the pages folder (the code is on its Send to server screen). Importing the same project again removes the files it no longer has, unless they were changed on the server |
| `/mcgui test wall\|view [cols] [rows] [player]` | Puts a test picture on the wall you look at, or opens one fullscreen: the framework working with no page at all |
| `/mcgui fov <degrees>` | Fits views to your FOV setting, for players without the pack (the server cannot read it) |
| `/mcgui pointer [speed]` | Your pointer speed in views, 0.25 to 4 times the server's (`views.pointer-speed`); without a number, shows it and the mouse sensitivity read from your turning |
| `/mcgui pack` · `stats` · `clear` | Re-send the pack · what the plugin costs the server now (see below) · remove test walls |
| `/mcgui debug <player> point <x> <y>` · `click` · `scroll <n>` · `speed` · `close` | Drive a player's view from the console; `speed` shows its pointer speed and where it came from |

### Permissions

Each subcommand has its own node, so a server can hand out one tool without
the rest. Pointing a command at another player needs that command's `.others`
node as well.

| Node | Allows | Default |
|---|---|---|
| `mcgui.open` | `/mcgui open <page>` for yourself | op |
| `mcgui.open.others` | `/mcgui open <page> <player>`; also grants `mcgui.close.others` | op |
| `mcgui.close.others` | `/mcgui close <player>` | op |
| `mcgui.place` | `/mcgui place` | op |
| `mcgui.remove` | `/mcgui remove` | op |
| `mcgui.list` | `/mcgui list` (pages, and where every screen is) | op |
| `mcgui.reload` | `/mcgui reload` | op |
| `mcgui.import` | `/mcgui import` (writes files into the pages folder, replacing any of the same name) | op |
| `mcgui.test` | `/mcgui test` for yourself | op |
| `mcgui.test.others` | `/mcgui test ... <player>` for somebody else | op |
| `mcgui.stats` | `/mcgui stats` | op |
| `mcgui.clear` | `/mcgui clear` (removes every test wall, whoever placed it) | op |
| `mcgui.debug` | `/mcgui debug` on your own view | op |
| `mcgui.debug.others` | `/mcgui debug` on another player's view | op |
| `mcgui.fov` | `/mcgui fov` | everyone |
| `mcgui.pointer` | `/mcgui pointer` | everyone |
| `mcgui.pack` | `/mcgui pack` | everyone |
| `mcgui.admin` | all of the above, in one line | op |

Closing your own page needs no permission, just as sneaking out of it needs
none. `mcgui.admin` is only a parent: the plugin checks the nodes it grants,
never `mcgui.admin` itself. Tab completion offers only what you may run.

**The mcgui.dev mark.** A page made on mcgui.dev carries
`<meta name="generator" content="mcgui.dev">`, and MC GUI draws it with a
faint mark: some blocks of plain colour are one chroma level bluer, in a
pattern mcgui.dev can read back from a PNG screenshot to tell the page came
from it. It holds a fixed tag and nothing about the player or the server.
`builder-mark: false` in `config.yml` draws every page exactly as written.

Wall screens are kept in `screens.yml`; a screen's name cannot contain a `.`.
A screen in a world that is not loaded when the server starts (one a
multiworld plugin loads later) goes up when that world loads. Each player
browses their own copy of a wall's page unless it was placed `shared`; on a
shared screen, commands, messages and links act for whoever clicked, with
placeholders filled in for them. Left-click a wall screen to click the
page; right-click it to sit down at that page fullscreen
(`walls.right-click-fullscreen`). Fullscreen pages fill the whole window,
whatever its shape and the player's FOV.

## Pointer speed

A fullscreen page's pointer moves a fixed distance for each step the mouse
reports (a *count*), the way a desktop pointer does, whatever the player's
Minecraft mouse sensitivity. `views.pointer-speed` in `config.yml` sets it:

| `views.pointer-speed` | Counts to cross the view | At 800 DPI | At 1600 DPI |
|---|---|---|---|
| **1.0 (default)**: a desktop pointer at Windows' default speed on a 1920-pixel-wide screen | 1920 | 6.1 cm | 3.0 cm |
| 1.5 | 1280 | 4.1 cm | 2.0 cm |
| 2.0 | 960 | 3.0 cm | 1.5 cm |

Each player can scale it for themselves with `/mcgui pointer <0.25 to 4>`.

Minecraft never tells the server a player's sensitivity, and no mouse ever
tells anybody its DPI, so neither can be asked for. But the client turns the
head by a whole number of counts times a step that depends only on the
sensitivity: `1.2 × (0.6 s + 0.2)³` degrees, for the option's `s` from 0 to 1
(shown in the game as 0 to 200%). Every change of pitch the client sends is a
whole multiple of that step, so MC GUI reads the step off the pitch of
every online player as they play, in a fraction of a second of looking around,
and remembers it on the player. Turns the server makes (teleports, respawns,
a view turning the player back inside the picture) are left out. Until it
knows, it assumes the game's default sensitivity (100%). It cannot read a
sensitivity while the turns are not whole counts (smooth camera on, a trackpad
sending fractions), and then keeps the last one it read. DPI stays the
player's own: like a desktop pointer, a higher DPI mouse moves it further for
the same hand movement.

With the pack the pointer is wherever the player looks (their client places
it every frame), so the view has to fit inside the turn a head can make: the
pointer is never slower than half the view's height per 75° of pitch. At the
default speed that only makes a difference above about 120% sensitivity, where
the pointer is faster than asked.

### The pointer and Minecraft's own menus

When a player opens a Minecraft screen over a page, the game frees the mouse
and their computer's own cursor appears. On Minecraft 26.2 with the resource
pack, the page's pointer goes away while the pause menu (or an options screen
opened from it) is up, and comes back where it was the moment they return to
the game. That works while "Menu Background Blur" is 1 or more, which is the
game's default (5).

For everything else players see both cursors until they close the screen:
chat, the inventory and other containers, and the pause menu with the blur at
0. Minecraft tells neither the server nor the resource pack when those are
open, so nothing can hide the pointer for them (NOTES.md has the reasons).
Mouse clicks go to the screen, not the page, while it is open.

## Custom cursors

The pointer is an arrow, a hand over anything clickable, a text caret or
nothing, as the page's CSS `cursor` says (`default`, `pointer`, `text`,
`none`). For a cursor of your own, put a PNG in `plugins/MCGUI/cursors/`
and restart the server: it goes into the resource pack, named by its file
name. A page asks for it the way a browser page does:

```css
.shop-item { cursor: url(sword.png) 4 0, pointer; }
```

It follows whatever is under the pointer, so each part of a page can have its
own, and anything clickable without one still gets the hand:

```css
body     { cursor: url(arrow.png), default; }
.mc-btn  { cursor: url(hand.png) 6 0, pointer; }
.mc-slot { cursor: url(grab.png) 8 8, pointer; }
.locked  { cursor: url(no.png) 8 8, default; }
```

The two numbers are the pixel that points, in the picture's own pixels (the
top left if left out). The keyword after it is what players see on a server
without that cursor. A picture 16 pixels tall is the arrow's size; up to 32 is
drawn bigger, and a taller one is scaled to 32 (128 a side at most, 64 cursors
at most). Players without the resource pack see it drawn into the map, in the
nearest map colours.

`/mcgui import` copies any picture an imported page uses as a cursor into the
cursors folder for you, and says when the server needs a restart to show it.
Plugins can use them too: `Pointer.custom("sword")`, and `.at(x, y)` for
another hot pixel.

## In a page

| Write | In game |
|---|---|
| `<a href="shop.html">` | goes to another page in the folder; `#id` scrolls to it |
| `data-command="spawn"` | runs a command as the player |
| `data-console="give {player} bread"` | runs a command as the console; not if a filled-in value has a space or `;` in it (a nickname, say), which is logged |
| `data-message="<gold>Hi {player}"` | sends the player a chat message (MiniMessage) |
| `data-action="close"` | closes the page; also `back`, `reload`, `top`, `page:shop` |
| `data-close` | (with a command) closes the page after running it |
| `data-event="buy"` | does nothing itself: it is for plugins, which get a `PageClickEvent` (see [For plugin developers](#for-plugin-developers)) |
| `data-sound="block.note_block.pling 1 1.5"` | the sound a click on it (or anything inside it) makes, even where nothing else happens: `<sound> [volume] [pitch]`, or `none` |
| `data-hover-sound="ui.button.click 0.1 2"` | plays when the pointer comes onto it |
| `data-key="1"` | pressing that key clicks it, fullscreen: `w a s d`, `space`, `sprint`, `f`, `q`, `1` to `9` (several with spaces). A page's own key wins over what the key does by default. The wheel moves the hotbar too, so a page can use any six of the number keys: the hotbar is rested away from the ones it uses |
| `<img src="head:{player}">` | a player's face, with their hat layer, crisp at any size; any name or UUID works (`head:Notch`). Online players' skins come from the server (skin plugins included), anyone else's from Mojang by name; a plain face stands in until it arrives |
| `href="https://..."` | sends the link to chat, where it can be clicked |
| `:hover`, `:active`, `cursor` | work; `pointer` turns the pointer into a hand, and `url(name.png)` is a [custom cursor](#custom-cursors) |
| `<details>`, checkboxes, radios | open and tick when clicked |
| `<select>`, `<input type="range">`, `<input type="number">` | a click picks the next option, sets the slider where it lands, or steps the number down (left half) or up (right half) |
| `<form data-command="buy {form.item} {form.amount}">` | a submit button in it runs the form's command (or `data-console`, `data-message`) with each field's value filled in; `<button type="reset">` puts the fields back. Any element's command can use `{form.<name>}` too, from the form it is in or the whole page |
| `{player}` `{uuid}` `{world}` `{x}` `{y}` `{z}` `{online}` `{max}` `{health}` `{level}` | filled in per player, in text and attributes; PlaceholderAPI's `%...%` too |

Page settings, all optional:

```html
<meta name="ui:size" content="12x7">    <!-- fullscreen size in maps (config: views.size) -->
<meta name="ui:height" content="576">   <!-- the page is laid out this many CSS px tall -->
<meta name="ui:refresh" content="5">    <!-- refill placeholders every 5 seconds (0.5 at the most often; 0: never) -->
<meta name="ui:scroll-step" content="tile"> <!-- a notch of the wheel: CSS px, or "tile" for one map row (config: scroll-step) -->
<meta name="ui:click-sound" content="none"> <!-- what a click that does something sounds like (config: click-sound, the game's button click) -->
<meta name="ui:open-sound" content="item.book.page_turn"> <!-- plays when the page opens -->
<meta name="ui:overlay" content="rgba(8, 10, 22, 0.55)"> <!-- an overlay: the world shows wherever the page paints nothing -->
```

A sound is any the game has, by its id (`entity.experience_orb.pickup`), or
your own: put an `.ogg` in `plugins/MCGUI/sounds/` and restart, and
`sounds/door.ogg` is `uiengine:door`. Only the player who clicked hears it.

An overlay page (`ui:overlay`) is drawn on nothing instead of white: leave
`html` and `body` transparent and put the page in a panel, and fullscreen the
player sees the world round it, under the tint the meta gives (`none` for
none). Opening it does not move their view at all, and the world round the
panel is the picture of it their game took as the overlay opened, so it stays
perfectly still while the pointer follows the mouse as fast as on any page.
It is frozen, too: water, mobs and other players behind the panel stop until
it closes, and the player is back exactly where they were, facing the same way.
Only the maps the panel covers are sent and drawn. Add `blur`
(`content="rgba(8, 10, 22, 0.3) blur"`) to blur the world round the panel, as
the game blurs it behind a menu, at the player's own menu blur setting. All of
that needs a 26.2 client; where the player has no room to be held still, the
world shows live round the panel and the server places the pointer (up to a
tick behind the mouse). On a wall, the wall behind shows through, untinted.
Players without the pack see the world round the panel too, without a tint.

Controls while a page is open: mouse moves the pointer, either button clicks,
the wheel (or W/S) scrolls, F goes back, sneak closes; and any key the page
gives a `data-key`.

Pages load in the background, so a page that fetches from the web never holds
up the server; it shows when it has loaded. With `allow-web`, a page can load
stylesheets, fonts and images from the web, but not from the server's own
machine or private network unless `allow-private-hosts` is on. Files are read
from the pages folder only.

A picture that uses more than `render-timeout` (2 s) of CPU time to draw is given up and
reported like a page that cannot be drawn, so one heavy page cannot hold a render
thread for good. It counts CPU time, not the clock, so a picture that is only slow because
the server is busy is not given up; one that has run five times the limit on the clock is,
however little CPU it got. Raise it (or set 0) for very large pages.

`/mcgui stats` shows what the plugin costs the server right now: its share of the
server thread each tick (time and bytes allocated, over the last 10 seconds),
how long the last pictures waited for a thread and took to draw, how many were
given up for `render-timeout`, its threads, and how long it took to start and
warm up. `/mcgui stats json` prints the same as one line of JSON.

The first picture of each page at each screen size is kept (`first-pictures` in
config.yml), so the next player to open that page there is sent the picture at
once rather than waiting for it to be drawn: a private wall sends each map once,
the page, and a fullscreen page opens already drawn. Every placed screen's page
is drawn for it when the plugin starts. Players who open a page at the same
moment wait for one drawing of it rather than each drawing their own.

A page with a player's own values in it (`{player}`, `{x}`, PlaceholderAPI) is
shared too, except where it shows those values (`sharing.personal-pages`, on by
default): the next player's first picture is the last one's with only their own
values drawn in (the market: about a tenth of a whole picture), and a hover one
player drew is taken by another whose picture is laid out the same round it, as
long as neither player's values are within reach of it. What each player sees is,
to the pixel, what their own page draws, and a click always runs their own
command. A value that moves the rest of the page (a long name that wraps a line
and pushes the boxes below it down) gets that player a picture of their own.
Values only in attributes nothing draws (`data-console="give {player} ..."`)
share everything.

Hovers are shared between players on any page (`hover-repaints` in config.yml):
everyone in the same state on the same page takes the one drawing of a hover, and
drawing ahead is never done twice for two players. Drawing ahead together uses at
most `hover-ahead-cpu` of a core (0.5 by default).

## What the renderer supports

Selectors (all combinators, attribute selectors, structural pseudo-classes,
`:not :is :where`, `:hover :active`, `::before ::after ::marker`), the cascade
with `!important`, inheritance, CSS variables, `calc() min() max() clamp()`,
`@media`, `@import`, `@font-face` (TTF/OTF; Google Fonts links work), block and
inline layout with margin collapsing, flexbox, grid (`repeat(auto-fill,
minmax())`, `fr`, areas, spans), tables, absolute/fixed/relative positioning,
`z-index`, `overflow`, `border-radius`, borders, `box-shadow`, `text-shadow`,
linear/radial/conic gradients, `background-clip: text`, background images,
`opacity`, `transform`, `filter`, `backdrop-filter: blur()`, `object-fit`,
`text-overflow: ellipsis`, `-webkit-line-clamp`, inline SVG and SVG images,
PNG/JPEG/GIF images, form controls drawn as in a browser, and CSS animations
(`@keyframes`, `animation`) and transitions (`transition`): numbers, lengths,
colours and transforms move smoothly, anything else changes halfway. While
something moves, the page is drawn `animation-fps` times a second (10 by
default), and each frame redraws only round what moves (see "Fast pages").

Fonts: Inter (sans-serif and every system UI font name), Source Serif 4
(serif), JetBrains Mono (monospace), Mac's Minecraft (`font-family: Minecraft`, the game's
own letters) and Monocraft (`font-family: Monocraft`, a monospaced take on them)
are bundled, all under the SIL Open Font License (`fonts/*-OFL.txt`).

Not supported: JavaScript, WOFF fonts, WebP images, video, iframes, floats
(floated boxes stay in the flow), typing into inputs.

## Fast pages

A hover is quick when the engine can redraw only the part of the page that
changed, and quicker still when it drew that hover before the pointer got there
(`hover-ahead`, the four hovers nearest where the pointer rests). Then a hover
shows in about 40 ms in game, most of it the client's own 50 ms wait between
reports of where the player is looking. A page that loses both is redrawn whole
on every hover: 100 to 300 ms on a busy page, which players feel as the pointer
arriving before the highlight does. What decides it:

- **A `transition` or `animation` costs nothing while it is not running.** The
  page keeps partial redraws and drawing ahead, and a hover that starts a
  transition is drawn ahead too (its first moment, never its end). While
  something moves, each frame redraws only round where the moving elements were
  and are now, so a small spinner costs the server under 10 ms a frame, not a
  whole picture (70 to 350 ms). How to keep animations cheap:
  - **Animate what painting alone reads**: `opacity`, colours, `transform`,
    `filter`, shadows. Then the page is not laid out again for each frame.
    Animating `width`, `height`, `margin`, `padding`, `top` or `font-size` lays
    the whole page out on every frame.
  - **Animations that repeat for ever are shared.** An `infinite` animation runs
    on the page's own clock, so every player looking at the page in the same
    state is shown the same frame: one is drawn, and the others are sent the
    same maps (50 players at a spinner cost about what two do). A transition,
    or an animation that ends, runs on each player's own clock, so each player
    watching costs a frame of their own.
  - **Keep what moves small.** Something large moving (a whole panel that bobs)
    is redrawn whole every frame, and a blur under or round what moves makes
    each frame as dear as a hover near it.
- **`:hover` with `+` or `~` turns drawing ahead off** (`.card:hover + .label`),
  as the hover can then change things outside the hovered element. Put the part
  that changes inside it instead (`.card:hover .label`).
- **Tooltips, lifts and highlights are cheap.** Showing a hidden element
  (`display: none` to shown), `z-index`, colours, borders and shadows on hover all
  redraw only round the change. Give a tooltip `pointer-events: none`, or one that
  covers its neighbours stops the pointer from reaching them.
- **Blur is the costliest thing there is to draw.** `filter: blur()` or
  `backdrop-filter` over a large area makes every redraw near it slower, and a
  `backdrop-filter` widens each hover's redraw to its reach. Drawing ahead hides
  that cost, so it bites on pages that have also lost drawing ahead. A blurred
  background box with nothing inside it (`<div class="bg"></div>` under
  `filter: blur()`, with no border or shadow) is blurred once per page and kept;
  put the content in a box of its own above it, not inside it.
- **Keep targets a reasonable size.** Only four hovers are drawn ahead, the ones
  the pointer's next move most likely lands on, so in a dense grid of small
  targets fewer of its next stops are ready. A hover that changes nothing (a
  card only raised with `z-index`) takes no turn.
- **Keep a player's own values away from what they hover.** On a page with
  `{player}` in it, a hover within reach of the name (or of a blur that reads it)
  is drawn for each player alone; everything further off is drawn once for all.

`sh gradlew hoverCost -Ppages=path/to/page.html` prints what each hover on a
page costs to redraw and how many fell back to a whole redraw (`not partial`). It
calls the redraw directly, so it does not see the `+`/`~` switch above: check
for that by eye. `sh gradlew animBench -Pdir=path/to/pages -Ppages=page` prints
what a page's animations cost the server a second at 1, 10 and 50 viewers
(`page:hover:.card` hovers each viewer over the cards in turn).

## Frame rate

What a page costs the player's own client, per frame, is mostly how many maps
are on their screen: every map is drawn on its own, however small it looks, at
about 10 µs of frame time each on a mid-range PC. The page's content, hovers and
animations hardly matter. Measured in the real client on a Ryzen 7 9700X with an
RTX 5060 (1280×720 window, no frame cap):

| On screen | Maps | FPS | Frame time |
|---|--:|--:|--:|
| The world alone | 0 | 1,890 | 0.53 ms |
| A fullscreen page at 16×9 (the default) | 144 | 400 | 2.5 ms |
| at 12×7 | 84 | 630 | 1.6 ms |
| at 11×6 | 66 | 750 | 1.3 ms |
| at 8×5 | 40 | 990 | 1.0 ms |

- **A fullscreen page's size is its frame rate.** `views.size` in `config.yml`
  sets every page's, `<meta name="ui:size" content="12x7">` one page's. The
  trade is sharpness: a view is drawn at 128 pixels a map, so 16×9 (2048×1152)
  is about one map pixel per screen pixel in a 1080p window, and 12×7
  (1536×896) is a little soft there, though fine at 720p. A menu of a few big
  buttons loses nothing at 12×7 or smaller; small text wants 16×9.
- **Wall screens add up.** Each map of a wall in sight is drawn on its own too,
  and the client draws wall screens as far away as the server sends their
  frames (Paper's `entity-tracking-range.misc` in `spigot.yml`, 96 blocks by
  default), so many screens in view cost the sum of their maps.
- **Overlay pages** (`ui:overlay`) cost about what an opaque page of the same
  size does; the world behind them is drawn as usual.

## For plugin developers

Add `depend: [MCGUI]` to your `plugin.yml` and compile against the plugin
(`compileOnly`, never shaded: it is one plugin on the server, which owns the
pack, the map ids and the seats). From a tag of this repository with
[JitPack](https://jitpack.io):

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { compileOnly 'com.github.resourcepack-ai:mcgui:<tag>' }
```

Pages, from `plugins/MCGUI/pages/`:

```java
MCGUI.open(player, "menu");   // menu.html, fullscreen
MCGUI.close(player);
MCGUI.page(player);           // the page they have open, or null
MCGUI.exists("menu");
```

What players do on pages comes as Bukkit events: `PageClickEvent` (cancellable,
before a click acts, with the element's attributes and the form's values),
`PageOpenEvent` and `PageCloseEvent`. An element with a `data-event` does
nothing but fire the click, so a page can hand a button to your plugin:

```html
<button data-event="buy" data-item="bread">Buy bread</button>
```

```java
@EventHandler
public void onClick(PageClickEvent e) {
    if ("buy".equals(e.getEvent())) shop.buy(e.getPlayer(), e.getAttribute("data-item"));
}
```

Your own pictures, drawn with anything that makes a `BufferedImage`:

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

A canvas with `canvas.transparency(true)` lets the world show through where an
image's alpha is under half, and `canvas.tint(argb)` is what a view lays over
the world there; `ViewOptions.DEFAULT.blur(true)` blurs the world round the
opaque part too (26.2 clients, not on peaceful).

The [examples](examples/) are whole plugins built this way:
[arcade](examples/arcade/) (thirteen games, on cabinets and fullscreen),
[atlas](examples/atlas/) (a live world map) and [vanilla](examples/vanilla/)
(pages in the game's own look).

## Licence

AGPL-3.0-or-later, with extra terms. See [`LICENSE`](LICENSE) and
[`NOTICE.md`](NOTICE.md). The bundled fonts keep their own
licence, the SIL Open Font License (`src/main/resources/fonts/*-OFL.txt`).
