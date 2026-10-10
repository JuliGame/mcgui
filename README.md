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
| `/ui list` · `/ui reload` | Pages and screens · read everything again |

Permissions: `uiengine.open` (yourself), `uiengine.open.others`,
`uiengine.admin` (place, remove, list, reload). All op by default.

Wall screens are kept in `screens.yml`. Each player browses their own copy of a
wall's page unless it was placed `shared`. Left-click a wall screen to click the
page; right-click it to sit down at that page fullscreen
(`walls.right-click-fullscreen`). Fullscreen pages fill the whole window,
whatever its shape and the player's FOV.

## In a page

| Write | In game |
|---|---|
| `<a href="shop.html">` | goes to another page in the folder; `#id` scrolls to it |
| `data-command="spawn"` | runs a command as the player |
| `data-console="give {player} bread"` | runs a command as the console |
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
<meta name="ui:refresh" content="5">    <!-- refill placeholders every 5 seconds -->
```

Controls while a page is open: mouse moves the pointer, either button clicks,
the wheel (or W/S) scrolls, F goes back, sneak closes.

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
