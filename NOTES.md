# UI Engine: notes for whoever works on it

HTML pages in game (2026-10-09), drawn through the framework in `framework/`:
pictures on walls of maps and in seated fullscreen views. It was
a plugin of its own, UI Framework, until 2026-10-23; its history is in this
repository's. One plugin now, one jar: the framework is still what other plugins
draw with (`UIFramework.get()`), and `examples/` holds two that do.
`README.md` is the user-facing description; this is the part that bites. Paths under `rigs/` are test rigs kept outside this repository.

## There is no browser, on purpose

It shows static pages and runs no Chromium. The Java options were checked:
Flying Saucer and OpenHTMLtoPDF stop at CSS 2.1 (no flexbox, no grid), JavaFX
WebView is a browser with native libraries, and CSSBox (pure Java, claims flex
and grid) lost variables, grid, the flex header, radii and shadows on an
ordinary modern page. So jsoup parses and everything after that is ours,
in `html/`, which has **no Bukkit in it** and is tested on its own:
`./gradlew renderSamples` renders `samples/*.html` to `build/samples/` at 1x
and 2x (and scrolled to the end, when a page scrolls). Judge changes there,
by eye, before going near a server. `-Pdir=src/main/resources/pages` renders
the example pages the plugin ships; `-Ponly=<name>` one page.

| Package | What |
|---|---|
| `html/css` | Parsing (`CssParser`, `Selector`, `Media`), the rule index (`Stylesheet`) and matching (`Matcher`, `AncestorFilter`), shorthand expansion, colour and value reading |
| `html/style` | The cascade (`Styler`, with the browser's default sheet `UA_CSS`), computed styles (`Style`), lengths (`Len`: px, %, and calc/min/max/clamp kept symbolic until layout) |
| `html/layout` | The box tree (`BoxBuilder`), and `Layout` with `InlineLayout`, `FlexLayout`, `GridLayout`, `TableLayout` |
| `html/paint` | `Painter` (stacking order, hit regions), backgrounds and gradients, shadows and blur, filters, transforms, form controls |
| `html/text` | Fonts (bundled OFL fonts, `@font-face`, fallback per character) and text measuring |
| `html` | `Page` (a file and everything it loads), `HtmlRenderer` (one call: style, lay out, paint) |
| top level | The plugin: `PageStore` (the folder, live reload), `Session` (one player's page on one screen), `Screens` (walls and views), `Placeholders`, `Sounds`, `UiCommand` |
| `framework` | The pictures on walls and in views that pages are drawn through, and other plugins' API; its own notes are below |

## Things that are not obvious

- **Animations work on declared values, as text** (`html/style/Motion`). When
  an element animates, the Styler replaces its declared values with the
  values of that moment before computing its style, so anything the cascade
  computes can move, layout included. The moment comes from `Motion.current()`,
  a thread-local the session sets around its drawing; with none (renderSamples,
  the benches) nothing moves. A page that moves (`Page.moves()`) is drawn whole
  every time: no partial repaint, scroll cache, drawing ahead or kept first
  picture, all of which assume a picture stays true until the state changes.
  Elements are keyed by their place in document order, as the Styler's memo
  is, so a transition survives the session's copy of the document changing.

- **Coordinates in the box tree are relative to the box that laid a box out**:
  its nearest ancestor that is not an inline box. Inline boxes have no
  rectangle, only per-line fragments relative to the block owning the lines.
  Painting and hit testing add offsets only on entering a non-inline box. Get
  this wrong and things inside a `<span>` drift.
- **`Layout.layout` is memoised on its inputs** (`Box.laidOutWith`). Flex and
  grid lay an item out several times (measure, size, stretch), and without the
  memo nested flex containers cost 2^depth. A layout that depends on something
  other than its four inputs breaks the memo.
- **Matching tries only the rules an element could match**, as browsers do: `Stylesheet` buckets each
  rule by its rightmost compound (id, `:root`, a class, an attribute name, a tag, or none) and by the
  pseudo-element it styles; `Matcher` skips a rule whose subject needs a name the element lacks (a
  64-bit mask) or whose ancestors need names no ancestor has (`AncestorFilter`, a counting Bloom filter
  the style walk pushes and pops). Both may only say "maybe": a selector feature that matches by
  something else than these names must not put its rule in a bucket, or contribute a hash. `MatcherTest`
  checks the index against trying every rule. Ids and classes ignore ASCII case only in quirks mode.
- **Styles are reused between passes over one document.** `Styler` keeps what a pass found (per element
  by place in document order, weakly by the root element, holding no elements) and the next pass with the
  same sheets and size starts from it: only rules that depend on :hover/:active are tried again, and an
  element whose rules came out the same under a parent that did keeps its `Style` object. Within a pass,
  elements with the same rules under the same parent style share one `Style`. So a `Style` must never be
  modified after `Style.compute`, and a document must never be edited once drawn (a click edits a fresh
  copy, `Session.editable`).
- **Measuring must not disturb a layout in progress**: `minContent`/
  `maxContent` resolve edges with an unknown containing block, so
  `intrinsicPair` saves and restores the box's margins, borders and paddings.
- **Rendering happens off the server thread**, one picture at a time per
  session (`Session.render` coalesces), into the session's own
  `Canvas`. Each session renders its own copy of the page's document (with the
  player's placeholders filled in), and anything a click changes (`<details>`,
  a checkbox) changes a fresh copy, so a picture being drawn never sees its
  document move. Whatever has to go back to the server thread (the next queued
  picture, a hover worked out again, a fragment scroll) goes through UI
  Framework's `serverThread()`, which runs between ticks, rather than
  `runTask`, which would wait for the next one.
- **So does loading pages.** `PageStore.load` hands back a future (one per
  page, however many ask) completed on a loader thread; sessions and views
  show the page when it completes, back on the server thread, and a session
  that has moved on meanwhile (`Session.navigation`) ignores it. Nothing on the
  server thread may wait for one: a page's web fetches can take 15 s each.
- **A session's `hover` and `pressed` are elements of its own `doc`**, which
  can be ahead of the last picture's document. Elements found in a picture are
  mapped into `doc` by their place in document order (`Session.counterpart`),
  which works because every copy of one `Page`'s document has the same shape;
  after a live reload the shape can differ, so nothing carries over then.
- **Surfaces are premultiplied** (`TYPE_INT_ARGB_PRE`), which Java2D blends
  fastest and the framework's `Canvas` reads in place. A layer with a `filter`
  stays straight `TYPE_INT_ARGB`, because `Filters` edits its pixels as
  straight colour.
- **A full picture is painted in up to four bands at once**, fewer when several pictures
  are painting (the framework's `Cpu` threads are split between them: a band costs CPU, see
  `sh gradlew loadBench`) (`HtmlRenderer.paintBands`), each by its own `Painter` into its own image,
  with a margin above and below as deep as the furthest blur can reach.
  The bands are deliberately not clipped: an extra clip changes how Java2D
  rounds overflow clips, by a pixel at their edges. Hit regions come from the
  first band, which paints every box of the page; the other bands make none. Partial (hover) repaints are
  one painter, worked out by `html/Repaint` (which also grows the painted area by a backdrop's reach and
  keeps only the changed part: `RepaintTest` compares every hover with a fresh picture), painting on an
  image of their own the size of the area, also unclipped (`HtmlRenderer.paintPart`): a clip cutting a
  rounded box drew a seam along its edge. `sh gradlew renderBench` times a page's full render, its
  `Canvas.draw` and a hover repaint.
- **A scroll is cut from a taller picture** (`html/ScrollCache`): the first
  notch paints up to four views of the page round the view (more ahead of the
  scroll than behind), and later notches copy the view's rows out of it, since
  painting is nearly all a picture costs. Hover and press repaints go into that
  picture, over their whole region rather than only what is in view. A page
  with anything `position: fixed` is drawn whole every notch. Scroll positions
  are whole canvas pixels (`ScrollCache.snap`), so a cut view matches a fresh
  one; band margins run past the picture's edges for the same reason (a blur at
  the view's edge reads the page beyond it). A session drops its cache after
  15 s of not scrolling, or on any change of document, page or size.
  `renderBench -Pscroll=1` times both ways and checks every cut view's pixels.
- **A scroll by whole map rows moves the canvas** (`Canvas.scroll`, in
  `Session.submit`): the framework then points each viewer's frames at the
  maps their client already has and sends only the rows that came in, in one
  bundle (about an eighth of the bytes of a notch on a 16x9 view). Only the rows that
  came in are drawn when the last view was cut from the same tall picture;
  otherwise the whole view is drawn over the moved canvas. A notch is whole rows
  only with `scroll-step: tile` (config or `<meta name="ui:scroll-step">`),
  except the one that reaches an end of the page. `TileScrollTest` rebuilds
  what the client shows from the packets and compares it with a fresh render;
  `sh gradlew tileBench` gives bytes, packets and CPU per notch.
- **A session's first picture of a page is shared** (`FirstPictures`, in
  `Session.submit`): keyed by the page revision (the `Page` object), the canvas
  size, the scale and every placeholder value filled in (`Placeholders.Filled`),
  and only for the first picture of a copy of the page (top, nothing hovered or
  pressed or clicked, no fragment, no value of the viewer's own). A session that
  finds it kept takes it on the server thread, copying nothing: `Canvas.show`
  hands its canvas the same tile arrays and colour buffer, and the session's
  frame and document are the picture's own, shared with every session that
  took it. Whatever draws copies first, on the render thread: the canvas its
  colours (`Canvas.detach`, at its first drawing), a repaint the frame's image
  (`borrowed`, `FirstPictures.own`). Documents and frames are never written to
  by drawing; a click that changes the document gives the session a copy
  (`Session.editable`). `Placeholders.fill` only works the values out (on the
  server thread); the filled copy is made on a render thread (`Filled.copy`)
  when the session draws, so `Session.doc` is null until the first picture.
  A new `Canvas` holds no colours until its first drawing. One session draws a
  missing picture and the others wanting it wait (`FirstPictures.claim`).
  Until its first picture is drawn a wall or view is given no canvas
  (`Session.showing`, at most `BLANK_AFTER_TICKS`), since a blank first is a
  second packet a map; a view still opens at once (its frames go up while the
  page is drawn) and the framework switches to it once the page has arrived. The warm-up
  draws every placed screen's page. `FirstPicturesTest` follows a taken and a
  drawn picture through hovers, presses, scrolls and refreshes, and
  `SharedPictureTest` does the same with real `Session`s (`SessionRig`: no
  server needed), checking the kept picture is never drawn into;
  `sh gradlew firstPictureBench` gives the CPU per new session both ways, and
  `sh gradlew sessionBench` the server thread's CPU and allocation per new
  viewer and the live heap per watching session.
- **A partial repaint takes in every blurred layer its region meets** that
  something which changed is inside or round (`Repaint.region`): a `filter:
  blur()` layer that the clip cuts would blur with a cut edge. A blurred layer
  the change is apart from (a blurred picture behind the whole page) is only
  painted as far round the region as its blur reads. A backdrop is painted
  again with a margin instead (see above). A blur that reads past the picture's
  top or bottom where the page goes on means a full picture, which paints the
  page there. A box that appears or goes on hover (a tooltip by `display`) is
  fine as long as it is inside the region (`Repaint.sameLayout`). An element
  whose hover only sets its own z-index (`.crate:hover { z-index: 2 }`, which
  lifts a card so a tooltip in it shows over its neighbours) is not repainted
  whole: only where its boxes overlap something they now come before or after
  in the painting order (`Stylesheet.orderOnly`, `Repaint.swapped`).
- **Backdrops blur on a grid fixed to the page** (`Painter.origin`, in device
  pixels from the page's top-left), so a band, a rectangle repaint, a whole
  view and a view cut from a taller picture all blur alike.
- **A steep gradient is not Java2D's** (`Backgrounds.StopPaint`): Java2D
  samples pixel corners in float, so a hard stop on a whole device pixel
  landed on a sample and a stripe's edge came out differently in a strip, a
  band and a repaint. Anything steeper than 8 levels a device pixel is worked
  out at each pixel's centre in double; gentler ones stay Java2D's, which is
  cheaper and within a level. `ScrollCacheTest`, `BandsTest` and `RepaintTest`
  each have a page of hard-edged stripes.
- **Gradient text is a mask**, not a clip: Java2D clips are not antialiased,
  and clipping a gradient to glyph outlines gave jagged letters.
- **Map pixels are not screen pixels.** A view is 16×9 maps (2048×1152) by
  default and the page is laid out at 576 CSS px tall, so it is drawn at 2x.
  Small text stays legible because the framework's shader averages the map
  pixels under each window pixel.
- **Google Fonts send TrueType to a user agent they do not recognise**, which is
  why `Page` asks for their CSS as plain "Mozilla/5.0": Java cannot read WOFF.
  Web fetches are cached in `plugins/UIEngine/cache/` and switched off with
  `allow-web: false`. `Page.Loader` follows redirects itself so it can refuse
  loopback and private addresses at every hop (`allow-private-hosts`), and
  reads `file:` URLs only inside the pages folder.
- **`data-console` runs as the console** with placeholders filled in at the
  click, for whoever clicked, from the attribute as the page file has it.
  `Placeholders.command` refuses the command (and logs why) when a filled
  value has whitespace or `;` in it: `{player}` and `{uuid}` never do, but
  `{display_name}` (nickname plugins), `{world}` (a folder name) and
  PlaceholderAPI values can, and would add arguments to a console command.
- **Permissions are one node per subcommand** (`uiengine.<sub>`, plus
  `.others` when the command names another player), checked in `UiCommand`
  through `permissionFor`. `uiengine.admin` is a parent in `plugin.yml` that
  grants them all and is never checked itself; `uiengine.open.others` is also
  a parent of `uiengine.close.others`, so whoever may open a page for somebody
  may close it again. A new subcommand needs its node in `plugin.yml`, under
  the parent too.

## Testing in game

Mineflayer cannot speak 26.3 and ViaBackwards kicks it for movement, so the
bot is no use here. Use a real client and drive the view from the console:
`/ui debug <player> point <x> <y>` then `click`, in canvas pixels (2048
wide for a default view). `/ui place <page> <cols> <rows> <name> <x> <y> <z>
<facing> [world]` places a wall without anybody aiming.

## The framework: layout

| Path | What |
|---|---|
| `api/` | The public surface: `UIFramework`, `Canvas`, `WallContent`/`WallScreen`, `ViewContent`/`View`, `Pointer`, `Click`, `Key` |
| `render/Palette` | The map colour table, and the HI (YCbCr in 240 colours) and PLAIN encodings |
| `pack/` | `Shaders` (both GLSL dialects), `SymbolHash` (the shader's colour lookup), `ResourcePack` (build, serve, who has it) |
| `net/` | `Nms` (every server internal, by reflection), `MapSender` (diffed map patches, per-player budget, frame re-pointing), `Wire`/`ServerWire` (what the sender writes through), `Pipeline` (ping answers, the hidden inventory, each connection's frame pointing) |
| `world/` | `MapWall` (the frames), `Walls` (placed walls and crosshair input), `Views` (seating, camera, pointer, input), `PrivateView` (fake air for players without the pack) |

## The framework: things that are not obvious

- **The server internals are reached by reflection**, so the plugin compiles against the plain 1.21.8 API and needs
  no paperweight. Every member is looked up once in `Nms`'s constructor; a
  missing one disables the plugin at startup with the reason. They were checked
  against Paper 26.2 and 26.3, which agree on all of them.
- **26.3 rewrote the shader dialect**: `layout(location = N)` on every varying,
  `#include` for `#moj_import`, both uniform blocks reordered, and order-
  independent transparency in `text.fsh`. `Shaders` is written once in the 26.3
  form and `to262` derives the old one; the pack ships 26.3 at its root and
  26.2 in the overlay `uiengine_26_2` (formats 88 to 96). An integer
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
- **Pictures skip the tick too.** `MapSender` runs on its own thread
  (`UIEngine-send`). It wakes when a canvas publishes a drawing, or every
  8 ms, and writes each player's patches straight to their channel with one
  flush (`Nms.writeNow`). `Connection.send` would not do: off the server
  thread it queues the packet until the next tick. `writeNow` drops the write
  while the player is being reconfigured (`isAcceptingMessages()` is false),
  when a game packet would fail to encode, and the tile is sent again later.
  Who sees which canvas is still worked out once a tick on the server thread.
  Pointer reports reach `ViewContent.pointer` through
  `UIFramework.serverThread()`, the server's own task queue, which Paper
  drains while it waits for the next tick: still the server thread, but not up
  to 50 ms late.
- **One pool for all CPU-heavy work: `api/Cpu`.** `cpu-threads` (default: the cores
  minus one) is the most threads that convert, encode or draw at once, for this
  plugin and any plugin on top of it (UI Engine's render jobs and painter bands run
  on it too). Never make another pool for work like that: pools add up, the sum
  passes the core count and the server thread waits for a core (Java priorities do
  nothing on Linux). Work on the pool may wait for work it forks (a pool thread
  that waits runs queued work, or hands its place to a spare, so a wait cannot use
  the budget up), but must not wait on the network or on the server thread.
  `MapSender` (one thread of its own), the pack's web server and netty are not
  part of the budget.
- **`Canvas.draw` converts on every core**, in bands of 32 rows on that
  pool. It reads `INT_RGB`, `INT_ARGB` and `INT_ARGB_PRE` rasters in place,
  sub-images included; any other type goes through `getRGB`, which is several
  times slower. Only HI tiles are made when a picture is drawn: a PLAIN tile is
  made the first time somebody without the pack looks at it.
- **A scroll by whole tiles moves maps, not pixels.** `Canvas.scroll(rows, ...)`
  rotates the picture and keeps each moved tile's arrays (and the HI_OWN and
  PLAIN tiles made from them). `MapSender` sees that a tile a frame should show
  is already in another of the wall's maps on that client, and points the frame
  at it (an item-frame data packet with a filled map of that id) instead of
  sending it; the maps left over take the rows that came in. Pointing is per
  player: the server's frames never change. The re-points and the new rows go
  in one `ClientboundBundlePacket`, which the client handles in one go between
  two frames. A frame the server sends a client afresh (spawned, shown again,
  resent after a cancelled click) would show its own map, so `Pipeline`'s
  `uiengine_frames` handler rewrites its item to the map it points at for
  that connection. The sender's own bundles never carry a new entity, which is
  how the handler tells them apart. Pointing lives as long as the connection
  (the client keeps its maps as long), so `MapSender.reset` keeps it.
- **Content is not trusted.** Every call into a `WallContent` or
  `ViewContent` from the tick, the sender or `Views.close` is guarded and
  logged, `LinkageError` included: a content whose plugin was disabled first
  throws `NoClassDefFoundError`. `Views.close` puts the player back (camera,
  game mode, potion, inventory) before it calls the content, so a content that
  throws cannot strand them.
- **The pointer waits for the seat teleport.** After a view appears, rotations
  are ignored until the client accepts the teleport to the seat (or 20 ticks
  pass): until then they still carry the old look direction and would throw the
  pointer across the screen.
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
- **Permissions are one node per subcommand** (`uiengine.<sub>`, plus
  `.others` when the command names another player), checked in
  `FrameworkCommand` through `permissionFor`. `uiengine.admin` is a parent
  in `plugin.yml` that grants them all and is never checked itself. A new
  subcommand needs its node in `plugin.yml`, under the parent too, and in
  `SUBCOMMANDS` so the check and tab completion see it.

## The framework: testing

`/ui test wall` and `/ui test view` need nothing else installed.
`/ui debug <player> point|click|scroll|close` drives a view from the
console, which is how a harness tests one without anybody at the mouse. The
shaders can only be judged in the real client.
