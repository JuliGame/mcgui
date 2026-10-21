# UI Engine

Put an HTML page in `plugins/UIEngine/pages/` and it is in the game: on a wall,
or fullscreen in front of the player, who points with their mouse and clicks.
Vanilla clients, no mods: a Paper plugin and a small resource pack it builds and
serves itself. Edit the file and it updates in game within a second.

No browser runs. UI Engine has its own HTML and CSS renderer, so what works is
**static** HTML and CSS: no JavaScript. Links, commands, hover states, scrolling,
sounds and a few form bits are made to work in game instead (below).

Under the pages is a framework any plugin can draw with: full-colour pictures on
walls and in fullscreen views, with the player's mouse, clicks and keys. The
[examples](examples/) include an arcade of games and a live world map built on
it. See [For plugin developers](#for-plugin-developers).

**Needs** Paper 26.2 or 26.3 (Java 25). PlaceholderAPI is optional. Build with
`./gradlew build` (JDK 17 or 21 to run Gradle); the plugin is
`build/libs/UIEngine-<version>.jar`. `./gradlew renderSamples` renders
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
- **Sounds.** Every `.ogg` in `plugins/UIEngine/sounds/` goes into the pack
  as a sound named after its path: `sounds/menu/open.ogg` plays as
  `uiengine:menu/open`, from a page, `/playsound` or any plugin. Names are lower
  case. Restart after adding one, so the pack is built again.

A new `pages/` folder starts with examples: `menu`, `rules`, `shop`, and
`market`, a night-market trader's stall that shows off hover tooltips, a
click-to-showcase item stand (radio inputs, no JavaScript), pixel-art item
sprites and pre-rendered 3D models, and a Trade button that runs a console
`give` with item components. Try `/ui open market`.

## Commands

| Command | Does |
|---|---|
| `/ui open <page> [player]` | Opens a page fullscreen. Sneak to leave |
| `/ui close [player]` | Closes it |
| `/ui place <page> [cols] [rows] [name] [shared]` | Puts a page on the wall you look at (the block becomes the top-left corner) |
| `/ui remove [name]` | Takes a wall screen down (or the one you look at) |
| `/ui list` · `/ui reload` | Pages and screens · read the config and every page again |
| `/ui test wall\|view [cols] [rows] [player]` | Puts a test picture on the wall you look at, or opens one fullscreen: the framework working with no page at all |
| `/ui fov <degrees>` | Fits views to your FOV setting, for players without the pack (the server cannot read it) |
| `/ui pack` · `stats` · `clear` | Re-send the pack · walls and bytes sent · remove test walls |
| `/ui debug <player> point <x> <y>` · `click` · `scroll <n>` · `close` | Drive a player's view from the console |

### Permissions

Each subcommand has its own node, so a server can hand out one tool without
the rest. Pointing a command at another player needs that command's `.others`
node as well.

| Node | Allows | Default |
|---|---|---|
| `uiengine.open` | `/ui open <page>` for yourself | op |
| `uiengine.open.others` | `/ui open <page> <player>`; also grants `uiengine.close.others` | op |
| `uiengine.close.others` | `/ui close <player>` | op |
| `uiengine.place` | `/ui place` | op |
| `uiengine.remove` | `/ui remove` | op |
| `uiengine.list` | `/ui list` (pages, and where every screen is) | op |
| `uiengine.reload` | `/ui reload` | op |
| `uiengine.test` | `/ui test` for yourself | op |
| `uiengine.test.others` | `/ui test ... <player>` for somebody else | op |
| `uiengine.stats` | `/ui stats` | op |
| `uiengine.clear` | `/ui clear` (removes every test wall, whoever placed it) | op |
| `uiengine.debug` | `/ui debug` on your own view | op |
| `uiengine.debug.others` | `/ui debug` on another player's view | op |
| `uiengine.fov` | `/ui fov` | everyone |
| `uiengine.pack` | `/ui pack` | everyone |
| `uiengine.admin` | all of the above, in one line | op |

Closing your own page needs no permission, just as sneaking out of it needs
none. `uiengine.admin` is only a parent: the plugin checks the nodes it grants,
never `uiengine.admin` itself. Tab completion offers only what you may run.

Wall screens are kept in `screens.yml`; a screen's name cannot contain a `.`.
A screen in a world that is not loaded when the server starts (one a
multiworld plugin loads later) goes up when that world loads. Each player
browses their own copy of a wall's page unless it was placed `shared`; on a
shared screen, commands, messages and links act for whoever clicked, with
placeholders filled in for them. Left-click a wall screen to click the
page; right-click it to sit down at that page fullscreen
(`walls.right-click-fullscreen`). Fullscreen pages fill the whole window,
whatever its shape and the player's FOV.

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
| `:hover`, `:active`, `cursor: pointer` | work; the pointer turns into a hand |
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
```

A sound is any the game has, by its id (`entity.experience_orb.pickup`), or
your own: put an `.ogg` in `plugins/UIEngine/sounds/` and restart, and
`sounds/door.ogg` is `uiengine:door`. Only the player who clicked hears it.

Controls while a page is open: mouse moves the pointer, either button clicks,
the wheel (or W/S) scrolls, F goes back, sneak closes; and any key the page
gives a `data-key`.

Pages load in the background, so a page that fetches from the web never holds
up the server; it shows when it has loaded. With `allow-web`, a page can load
stylesheets, fonts and images from the web, but not from the server's own
machine or private network unless `allow-private-hosts` is on. Files are read
from the pages folder only.

A picture that takes longer than `render-timeout` (2 s) to draw is given up and
reported like a page that cannot be drawn, so one heavy page cannot hold a render
thread for good. Raise it (or set 0) for very large pages.

The first picture of each page at each screen size is kept (`first-pictures` in
config.yml), so the next player to open that page there is sent the picture at
once rather than waiting for it to be drawn: a private wall sends each map once,
the page, and a fullscreen page opens already drawn. Every placed screen's page
is drawn for it when the plugin starts. A page with a player's own values in it
(`{player}`, `{x}`, PlaceholderAPI) is drawn for each player as before; its
screens still show nothing until the page is drawn, rather than black first.

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
colours and transforms move smoothly, anything else changes halfway. A page
that moves is drawn whole, `animation-fps` times a second (10 by default), for
each player watching it, for as long as something on it moves, and only what
changed is sent: cheap for a hover that fades, not for a page that spins for ever.

Fonts: Inter (sans-serif and every system UI font name), Source Serif 4
(serif), JetBrains Mono (monospace) and Monocraft (`font-family: Minecraft`)
are bundled, all under the SIL Open Font License (`fonts/*-OFL.txt`).

Not supported: JavaScript, WOFF fonts, WebP images, video, iframes, floats
(floated boxes stay in the flow), typing into inputs.

## For plugin developers

Add `depend: [UIEngine]` to your `plugin.yml` and compile against the plugin
(`compileOnly`, never shaded: it is one plugin on the server, which owns the
pack, the map ids and the seats). From a tag of this repository with
[JitPack](https://jitpack.io):

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { compileOnly 'com.github.resourcepack-ai:ui-engine:<tag>' }
```

Pages, from `plugins/UIEngine/pages/`:

```java
UIEngine.open(player, "menu");   // menu.html, fullscreen
UIEngine.close(player);
UIEngine.page(player);           // the page they have open, or null
UIEngine.exists("menu");
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

The [examples](examples/) are whole plugins built this way:
[arcade](examples/arcade/) (thirteen games, on cabinets and fullscreen),
[atlas](examples/atlas/) (a live world map) and [vanilla](examples/vanilla/)
(pages in the game's own look).

## Licence

AGPL-3.0-or-later, with extra terms. See [`LICENSE`](LICENSE) and
[`NOTICE.md`](NOTICE.md). The bundled fonts keep their own
licence, the SIL Open Font License (`src/main/resources/fonts/*-OFL.txt`).
