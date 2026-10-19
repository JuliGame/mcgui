# UI Engine

Put an HTML page in `plugins/UIEngine/pages/` and it is in the game: on a wall,
or fullscreen in front of the player, who points with their mouse and clicks.
Vanilla clients, no mods. Edit the file and it updates in game within a second.

No browser runs. UI Engine has its own HTML and CSS renderer, so what works is
**static** HTML and CSS: no JavaScript. Links, commands, hover states, scrolling
and a few form bits are made to work in game instead (below).

**Needs** [UI Framework](https://github.com/resourcepack-ai/ui-framework) (it draws the pictures) on Paper
26.2 or 26.3. PlaceholderAPI is optional. Build with a clone of
[ui-framework](https://github.com/resourcepack-ai/ui-framework) beside this one
(`../ui-framework`), then `./gradlew build` (JDK 17 or 21 to run Gradle); `./gradlew
renderSamples` renders `samples/*.html` to `build/samples/` to check the
renderer by eye.

## Commands

| Command | Does |
|---|---|
| `/ui open <page> [player]` | Opens a page fullscreen. Sneak to leave |
| `/ui close [player]` | Closes it |
| `/ui place <page> [cols] [rows] [name] [shared]` | Puts a page on the wall you look at (the block becomes the top-left corner) |
| `/ui remove [name]` | Takes a wall screen down (or the one you look at) |
| `/ui list` · `/ui reload` | Pages and screens · read the config and every page again (`render-threads` needs a restart) |

### Permissions

Each subcommand has its own node, so a server can hand out one tool without
the rest. Pointing a command at another player needs that command's `.others`
node as well. All are op by default.

| Node | Allows |
|---|---|
| `uiengine.open` | `/ui open <page>` for yourself |
| `uiengine.open.others` | `/ui open <page> <player>`; also grants `uiengine.close.others` |
| `uiengine.close.others` | `/ui close <player>` |
| `uiengine.place` | `/ui place` |
| `uiengine.remove` | `/ui remove` |
| `uiengine.list` | `/ui list` (pages, and where every screen is) |
| `uiengine.reload` | `/ui reload` |
| `uiengine.admin` | all of the above, in one line |

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
| `href="https://..."` | sends the link to chat, where it can be clicked |
| `:hover`, `:active`, `cursor: pointer` | work; the pointer turns into a hand |
| `<details>`, checkboxes, radios | open and tick when clicked |
| `{player}` `{uuid}` `{world}` `{x}` `{y}` `{z}` `{online}` `{max}` `{health}` `{level}` | filled in per player, in text and attributes; PlaceholderAPI's `%...%` too |

Page settings, all optional:

```html
<meta name="ui:size" content="12x7">    <!-- fullscreen size in maps (config: views.size) -->
<meta name="ui:height" content="576">   <!-- the page is laid out this many CSS px tall -->
<meta name="ui:refresh" content="5">    <!-- refill placeholders every 5 seconds (0.5 at the most often; 0: never) -->
<meta name="ui:scroll-step" content="tile"> <!-- a notch of the wheel: CSS px, or "tile" for one map row (config: scroll-step) -->
```

Controls while a page is open: mouse moves the pointer, either button clicks,
the wheel (or W/S) scrolls, F goes back, sneak closes.

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
PNG/JPEG/GIF images, form controls drawn as in a browser.

Fonts: Inter (sans-serif and every system UI font name), Source Serif 4
(serif), JetBrains Mono (monospace) and Monocraft (`font-family: Minecraft`)
are bundled, all under the SIL Open Font License (`fonts/*-OFL.txt`).

Not supported: JavaScript, WOFF fonts, WebP images, video, iframes, CSS
animations and transitions, floats (floated boxes stay in the flow), typing into
inputs.
