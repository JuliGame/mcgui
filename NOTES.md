# MC GUI: notes for whoever works on it

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
  the benches) nothing moves. Elements are keyed by their place in document
  order, as the Styler's memo is, so a transition survives the session's copy
  of the document changing. How a page that moves (`Page.moves()`) costs only
  what moves:
  - **Still is free.** While `Motion.active()` is false (nothing running or
    waiting), `Session` draws it as a page that never moves: partial repaints,
    the scroll cache, drawing ahead and kept first pictures. Those carry the
    motion they were styled with (`Repaint.Prepared.motion`,
    `FirstPictures.Picture.motion`), which the session takes on with
    `Motion.copyAt`, so a transition a drawn-ahead hover starts begins at its
    first moment, never its end (`TransitionHoverTest`).
  - **A frame repaints what moved.** `Motion.moved()` is the elements that moved
    (or waited to) in the last pass; the next frame repaints them
    (`Repaint.partial` over the last frame, with `Repaint.MOTION_MARGIN`, since
    old and new places both come from the hit regions), or the whole picture if
    that is too big (`AnimationTest`). A whole picture's bands are all of one
    moment, so a grid blur under a moving page matches in every band (`BandsTest`).
  - **Paint-only frames keep the layout.** The motion keeps the last picture's
    box tree (`Motion.keepLayout`, opaque there). `HtmlRenderer.lay` restyles,
    and if every style differs from the last only in `Style.PAINT_ONLY` fields
    (checked by reflection, so a new `Style` field counts as layout until named
    there), it swaps the new styles into the boxes and skips building and layout.
    Not when a transform appears or goes on a box holding absolute boxes, or an
    inline SVG's colour moves (baked into its picture).
  - **Two clocks.** Transitions and animations that end run on the viewer's
    clock (from when the page was shown); animations that repeat for ever on the
    page's (the server tick), so while only those move (`Motion.personal()` false)
    every viewer of one document in one state wants the same picture each tick:
    `AnimFrames` lets one draw it and the others show its tiles. A picture taken
    is shared for good: its drawer copies it before painting over it
    (`retireShared`), as with a first picture.

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
- **`render-timeout` counts the drawing thread's CPU, not the clock** (`RenderBudget`): a picture slowed only by a
  busy machine is not a page that cannot be drawn. A render still gives up at 5 times the limit on the clock
  (`RenderBudget.WALL_TIMES`), so bands painted on other threads cannot run unbounded. The budget must be made on
  the thread that draws (`HtmlRenderer.render` does, inside the job), as it reads that thread's CPU time.
- **Render jobs queue in `Renders`, not in the pool, most urgent first.** The pool's own queue is first come first
  served, so a page somebody had just opened waited behind every hover and animation frame already queued (4 s
  median on a loaded server). `Renders` hands the pool at most one job per pool thread, from the most urgent
  `Renders.Kind` that has one waiting: `SHOW` (a page's first picture) > `PICTURE` (hover, click, scroll, refresh) >
  `WARM` (start-up warm-up) > `MOTION` (an animation's next frame; late, it is a dropped frame) > `AHEAD` (a
  hover drawn ahead, a guess). First come first served within a kind. `Session.submit` picks the kind; give every
  new kind of render job one, and add it to the enum in its place, not at the end. `idle()` is false while
  anything waits, so drawing ahead never starts then.
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
- **An overlay page is painted on nothing** (`<meta name="ui:overlay">`):
  every `Painter` of such a page decides so itself, from the page it is given,
  and clears the picture where it would fill it white, so an image that held
  another picture (a pooled one, a band's, the base a repaint goes over) keeps
  none of it where the page paints nothing, at every moment of a page that
  moves too. A new way of painting needs nothing extra to get it right. Its
  alpha is where the world shows: `Session` and the kept first pictures give
  the canvas transparency and the page's tint with every drawing. `OverlayTest`
  checks the market demo, its hovers, a hover next to the transparent part,
  and a panel sliding across a reused image.
- **A full picture is painted in up to four bands at once**, fewer when several pictures
  are painting (the framework's `Cpu` threads are split between them: a band costs CPU, see
  `sh gradlew loadBench`) (`HtmlRenderer.paintBands`), each by its own `Painter` into its own image,
  with a margin above and below as deep as the furthest blur can reach.
  The bands are deliberately not clipped: an extra clip changes how Java2D
  rounds overflow clips, by a pixel at their edges. Hit regions come from the
  first band, which paints every box of the page; the other bands make none. Partial (hover) repaints are
  one painter, worked out by `html/Repaint` (which also grows the painted area by a backdrop's reach and
  keeps only the changed part: `RepaintTest` compares every hover with a fresh picture), painting on an
  image of their own, also unclipped (`HtmlRenderer.paintPart`): a clip cutting a rounded box drew a seam
  along its edge. That image starts at the picture's left edge, not the area's: Java2D adds up a run's glyph
  advances in floats from where it starts on the image, so text drawn from elsewhere could land a glyph a
  pixel column over. A layer's filter, blurred shadows and backdrops are still only worked out round the area
  (`Painter.keeping`), and so is everything else: a box, inline box, text run or background tile wholly outside
  the area (grown by `Painter.CULL_MARGIN`, and by a shadow's reach or a text's em) is not drawn, only its hit regions
  made (`Painter.drawnBounds`, `paints`), and a gradient is filled over the surface only (`Backgrounds.layer`). The image is not cleared first
  (`PixelPool.uncleared`: `Painter.paint` sets every pixel of the surface itself, in device space, with one fill). The new state is laid out first (`HtmlRenderer.layOver`), so a
  hover that shows a tooltip is painted once, over the old and new areas together (`paintOver`), not
  painted, found to reach further, and painted again. `sh gradlew renderBench` times a page's full render, its
  `Canvas.draw` and a hover repaint.
  What page authors can do about all this (and the switch that turns drawing ahead off for a whole page,
  `Page.siblingStates`, checked in `Session` and not by `hoverCost`, which calls `Repaint.partial` directly; and
  what a page that moves costs while it moves) is in the README's "Fast pages".
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
  took it. Whatever draws copies first, on the render thread: the canvas the
  maps it draws into (`Canvas.own`, per 128x128 map), a repaint the frame's image
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
- **Hover repaints are shared by every session** (`SharedRepaints`, one per plugin, LRU by bytes): keyed by
  document (by identity), page revision, size, scale, scroll and the hover/press state drawn and wanted
  (`HoverAhead.key`). `Session.submit` claims the key on the server thread; a session whose hover another is
  drawing does not hold a render thread but re-submits when it lands (`repaintLanded`, as FirstPictures'
  `landed`). A repaint known not partial is kept as null (drawn whole by the next without trying). Drawing ahead
  puts into the same table, so nothing is drawn ahead twice for two sessions. Pages that move, or whose images
  arrive late, key by the picture itself, which nobody else has (never shared; forgotten on `drop`).
  `SharedHoverTest`, `SharedRepaintsTest`.
- **Personal pages share what is not the player's own** (`html/Personal`, `sharing.personal-pages`). The
  invariant is pixels: a player's picture is, to the pixel, what their own copy draws (a fresh picture for a first
  picture; the player's own session's repaint, which is within a level of fresh as every repaint chain is, for
  hovers), and their frame, hit regions and commands are their own copy's. Copies of one template
  (`Placeholders.Filled.template`) match element by element by document order (`Personal.Copy`); an element is
  the player's own if its own text holds a personal value, or a drawn attribute does (`src`, `value`... or any a
  selector reads; `data-*`, `href` and the like are not). A first picture of a personal page is kept under its
  template (`FirstPictures.Key.PERSONAL`), never adopted as it is: the taker retargets its frame to its own copy
  and repaints its own elements over it with `Repaint.retext`, which also takes in whatever the new layout moves
  (a flex spacer beside a longer name) and falls back to a whole picture if that is too much; the canvas shows
  the other's tiles with the patch drawn in as one change (`Canvas.show(from, patch, x, y)`), so their name is
  never published to this canvas. A hover repaint is kept under a template key (`Personal.Key`) with the picture
  it was worked out over (`Prepared.origin`); a taker uses it only if both pictures are laid out alike round what
  it changes (`Personal.translate`: every box near `Personal.zone`, the changed pixels grown by the blurs the
  repaint and the page read, the same by place and painting order) and neither player's own values paint into
  that zone (`Personal.clear`); its frame is then built from the repaint's boxes near it and the taker's own
  elsewhere. A repaint whose zone a player's values reach is kept as `own` (`SharedRepaints.putOwn`): each copy
  draws its own, nobody waits. `PersonalShareTest` guards it: two names of different lengths in a fixed header
  (shared, every picture checked against a fresh one), a name that wraps and pushes the cards down (nothing
  shared), a click on a shared picture (the clicker's own command), six players opening at once (one whole
  picture), and the shipped market.
- **What an open view holds** (round 4, Memory). A canvas keeps its colours a map at a time (`Canvas.ycc`, one
  128x128x3 block per tile, null for black), shared copy-on-write per map by `copy`/`show`; a session's canvas also
  shares alike (`Canvas.shareAlike`, `Canvas.Alike`): every tile it encodes is looked up by its colours (and palette,
  size, transparency) among every live tile, so players' views of one page publish one `Tile` (its HI, HI_OWN,
  HI_TILED, PLAIN and colours) wherever they look alike, and a viewer who has it is sent nothing. A shared map is
  never drawn into in place: each changed map costs a 48 KB copy on the next drawing (why arcade canvases do not
  share alike). HI_TILED is kept on the `Tile` for the first place asked (`Tile.tiled`), per canvas only elsewhere.
  A session's frame image is put away after `park-pictures-after` (5 s) with nothing drawn and the pointer still
  (`Session.park`, a `PARK` render job): `ParkedPicture` keeps it in 128x128 int blocks, each shared with every
  parked block of the same pixels (weakly held, by hash then content), the frame keeps no image, and
  `HoverAhead.letGo` drops the frames it held. `Session.submit` puts it back first, on a render thread, then submits
  again (a few ms, once per rest); `lookAhead` waits for that. Not parked: a borrowed (shared) image, a page that
  moves or is schemed, a frame with a scroll cache. `Personal.COPIES` holds copies weakly (a `Copy` refers to its
  document, so a strong value kept every copy ever made with its last measured picture, a leak of about 9 MB a copy); the
  session (`ownCopy`), a first picture (`Picture.copy`) and a shared repaint (`Prepared.keeps`) hold them, and
  `Copy.measured` is weak. Tests: `SharedTilesTest`, `ParkedPictureTest`, `PersonalCopiesTest`,
  `PersonalShareTest.aPictureParkedAtRestComesBackAsItWas`. Measuring: `rigs/r4/Memory/mem.py` (class
  histograms with views opened for a third of the players, then the rest: the difference per added view leaves the
  plugin's own caches out) and `Hprof.java` there (dominator tree of a heap dump, by owner and field).
- **Drawing ahead ranks by where the next report lands** (`HoverAhead.rank`): points spread evenly over a disc
  round the pointer (`REACH` 56 CSS px at rest; ahead of it and wider when it moves) are looked up as a hover
  would be, and each state scores the share of the disc it covers; the current state is left out. Up to `most`
  repaints that change pixels are drawn (one that changes none, a card only raised, takes no turn), trying at most
  twice as many. All of it, every session's together, uses at most `hover-ahead-cpu` of a core on average in
  bursts of half a second (`HoverAhead.budget`), on top of the one-at-a-time rule and `Renders.idle`. Offline
  coverage: `AheadCheck` (test sources) on the hover-latency trials in `rigs/hl/targets.json`: crates
  went from 16 to 23 of 23 trials drawn ahead at rest (4 to 23 after a jump), market, quests and auction stayed at
  all of them while drawing 25/28/43 repaints instead of 56/44/68. `HoverAheadTest.theRowThePointerGlidesOntoIsDrawnAhead`.
  `/mcgui stats` prints how many drawn ahead were used and the CPU drawing ahead took.
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
  view and a view cut from a taller picture all blur alike. So does a wide
  `filter: blur()` that is the filter's only function (`Filters.gridBlur`,
  `Painter.gridBlurred`): worked out `Blur.step` times smaller, as a backdrop
  is (from sigma 6 on, a 6px backdrop at 2x: half size). The vanilla pages' blurred panorama was most of every
  hover repaint there.
- **A blurred box with nothing in it but its background is blurred once per page**
  (`Painter.fromKept`, `BlurredLayers`): a wide `filter: blur()` box with no children, text,
  borders or shadows (the vanilla pages' panorama) is painted and blurred whole, over the page within
  a whole-pixel clip, and kept per page and per everything its pixels depend on (the key in
  `fromKept`); every picture, band and repaint after draws its cells from it. A surface's own
  blur carries its edge pixels on past its edges where the kept one reads real ones, so it is
  used only where every pixel kept (`Painter.kept`, else `keeping`, else all) reads nothing past
  the surface. `BlurredLayersTest` checks it against the same box painted every time.
- **A blur reads and writes in memory order** (`Blur`): each of its three boxes
  sums along the rows, then down the columns with a running sum per column, two
  channels to an int and the division looked up. Exactly the old pixels, at up
  to 2.5 times the speed on a big picture (the old way wrote each pass
  transposed). A shadow, one colour, blurs only its alpha (`Blur.shadow`), and
  a text-shadow that is not blurred is the text drawn again in its colour
  (`Painter.glyphs`): filling its glyphs' outlines cost five times as much.
  The ring of an inset shadow, the cut-out of an outer one and a border of one colour are even-odd
  paths (the same pixels as an `Area`, without its cost), and on
  whole device pixels (boxes at 2x) a square border or sharp box shadow is filled as up to four
  rectangles, not rasterised (`Shapes.fillPixels`): the vanilla pages' bevels were a third of a picture.
- **A conic gradient finds its colour by table** (`Backgrounds.ConicPaint`): a pixel's angle comes from a
  polynomial of atan (4e-8 rad), and the colour from a table of 4096 cells where a cell, with a margin that dwarfs
  that error, is one colour (between two stops of one colour, as the market's rays are); every other pixel, and
  any near the centre, still goes through `atan2`. `ConicGradientTest` checks millions of pixels against `atan2`.
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
  Web fetches are cached in `plugins/MCGUI/cache/` and switched off with
  `allow-web: false`. `Page.Loader` follows redirects itself so it can refuse
  loopback and private addresses at every hop (`allow-private-hosts`), and
  reads `file:` URLs only inside the pages folder.
- **`data-console` runs as the console** with placeholders filled in at the
  click, for whoever clicked, from the attribute as the page file has it.
  `Placeholders.command` refuses the command (and logs why) when a filled
  value has whitespace or `;` in it: `{player}` and `{uuid}` never do, but
  `{display_name}` (nickname plugins), `{world}` (a folder name) and
  PlaceholderAPI values can, and would add arguments to a console command.
- **Permissions are one node per subcommand** (`mcgui.<sub>`, plus
  `.others` when the command names another player), checked in `UiCommand`
  through `permissionFor`. `mcgui.admin` is a parent in `plugin.yml` that
  grants them all and is never checked itself; `mcgui.open.others` is also
  a parent of `mcgui.close.others`, so whoever may open a page for somebody
  may close it again. A new subcommand needs its node in `plugin.yml`, under
  the parent too.

## Testing in game

Mineflayer cannot speak 26.3 and ViaBackwards kicks it for movement, so the
bot is no use here. Use a real client and drive the view from the console:
`/mcgui debug <player> point <x> <y>` then `click`, in canvas pixels (2048
wide for a default view). `/mcgui place <page> <cols> <rows> <name> <x> <y> <z>
<facing> [world]` places a wall without anybody aiming.

**A 26.3 client** (shaders, post effects, the pointer): the pack ships both
dialects and only a real client of each version can judge them. On the test PC 26.3 is installed beside 26.2 and runs
as its own account with its own game folder: `client-start.sh Bot263 26.3`
(options and server list copied from the 26.2 one, or
it stops at the resource pack prompt), `client-stop.sh Bot263`. Its probe
agent is the same source built against the 26.3 `client.jar`
(port 25612; `launch.ps1 -Version` picks
it); `snap`, `ptrwatch`, `mouse` and `gui` all work. The PC's server is 26.2,
so put ViaVersion (5.12.2 knows 26.3) in its plugins for the session: a 26.3
client then joins it cleanly (the plugin turns shared compression off for a
connection Via handles). `pc-restore.sh` removes it again. Improved
transparency (26.3's part of Fabulous) is `improvedTransparency` in the 26.3
game folder's `options.txt`. To see what a version the chain was never
checked in gets, build with that dialect's protocol in `Shaders.Dialect`
changed: the overlay must open on the server pointer, the world must not turn
with it. The rig and every result of the 26.3 port are in
`rigs/r4/Ver263/` (`pc263.py`, `validate.py`).

## Measuring: `/mcgui stats` and the bench

- **`/mcgui stats` is what the plugin costs a live server** (`Stats`; `/mcgui stats json` for scripts).
  `framework/TickMeter` times the plugin's two tick tasks (the framework's and the screens') and reads the server
  thread's allocation counter round them, per tick, over the last 200 ticks: two clock reads and two counter reads
  a task, nothing allocated. `Renders` keeps, per kind of job (a first picture, a picture somebody waits for, the
  warm-up, an animation frame, a hover drawn ahead), how long the last 512 waited for a pool thread and ran.
  `RenderBudget` counts the renders it gave up. Anything new that runs every tick on the server thread belongs
  inside the metered tasks.
- **Nothing runs at rest, and a quiet tick allocates next to nothing.** With nobody at a screen the send loop sleeps,
  the arcade's loop is stopped, a cabinet nobody is in range of pauses, and the page loader threads time out.
  A tick in which nobody comes, goes or changes what they look at reuses the last tick's lists: `Walls.viewers`
  hands out the wall's own list (read it, never keep it), a player is tested against a wall from `Entity.getX`
  and friends (a `Location` or `getEyeLocation` allocates, and `getLocation(Location)` makes a `WeakReference`),
  `Walls.aim` runs only for players within reach of a wall, `Framework.work` keeps each wall's
  `MapSender.Assignment`s from tick to tick while the viewer, canvas and encoding are the same (a new one only for
  what changed), and hands the sender a new list only when it differs. `Placeholders.apply` works out a value only
  if its token is in the text (the display name's plain text alone allocated about 200 KB a call). The `idle`,
  `walls` and `views` cells show `uie_tick_bytes`: about 380 bytes a tick with no screens at 0 to 100 players; 700
  bytes with 8 walls near 100 players (was 25 KB, an assignment per viewer per tick); still 34 KB with 50 idle
  views (mostly `Views.keepStill`'s entity lookup per view per tick).
- **`python3 bench/bench.py` gives every part's headline numbers in one run** (`--quick` for a shorter one,
  `--only <cells>`, `--compare <earlier json>`; `--help` for the rest). It builds the plugin and the arcade
  example, waits for the machine to be quiet (`gate.sh`), and holds the timing lock throughout. Then it runs the
  headless benches on the test classpath and a private copy of a Paper server with MC GUI, the arcade and the
  Probe plugin (kept outside this repository), whose fake players are real connections made inside the server
  that answer what a client must and turn their heads like one. The server's ticks and threads come from Probe
  (`ticks`, `fake status`, `health`), the plugin's own cost from `/mcgui stats json`. It prints one line per number
  and writes them all, with the raw replies, to `build/bench/<time>.json`. The server's heap is 3 GB (`--heap`):
  the `deadline` cell holds 60 open views at about 23 MB each, and at 2 GB it spent its time in full collections
  (a 37 s tick) whichever build ran. The cells:

  | Cell | What it does | Headline numbers |
  |---|---|---|
  | `headless` | `HoverCost` on the market (and the vanilla crates, auction and quests with `--vanilla`), `RenderBench` on the market | hover repaint CPU per page (p50, p90); a whole market picture |
  | `startup` | the server just started, nobody on | enable and warm-up ms, the plugin's threads, its CPU at rest |
  | `idle` | 0, 50 and 100 players standing still, nothing open | the plugin's server thread µs and bytes a tick, server tick p50/p99 |
  | `look` | 100 players turning their heads, nothing open | the same: what reading their mice costs |
  | `walls` | 8 shared 4x3 market walls in view of 100 players, nobody aiming | the same, and the sender's CPU |
  | `views` | 50 players at the market fullscreen, still | the same, the sender's and pool's CPU, heap per view |
  | `hover` | 10 and 50 players sweeping round the market | cores used, pool CPU, tick p50/p99, MB/s sent, pictures and drawings ahead |
  | `latency` | one player at the market, the pointer moved by `/mcgui debug point` | ms until the hover's map patches reach them: cold, and after resting next to it |
  | `anim` | 1, 10 and 50 players at `bench/pages/bench-anim.html` (a spinner, cards that fade) | cores, pool CPU, pictures a second, MB/s |
  | `deadline` | 40 hovering the market, 10 at the spinner, a busy loop on every core but one, while 10 more open a market with their name in it (a whole first picture each) and the traced player opens it six times | renders given up, ms until the traced player's whole picture arrives (median, most), each kind of job's wait and run p50/p99/max |
  | `arcade` | four cabinets with nobody in range; then each game on a 16x9 cabinet playing itself, one player watching | CPU (game loop, pool, sender) at rest and per game, and MB/s per game |

  Client-side numbers (frame time, hover to screen in the real client) are not in it: they need Josh's PC and
  the probe agent (outside this repository).

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
  26.3 compiles every shader with shaderc as Vulkan GLSL into SPIR-V (and back
  to GLSL 330 through SPIRV-Cross on OpenGL; `renderpearl` `GlslCompiler`,
  `GlPipelineRecompiler`), so a 26.3 post shader needs the same `layout`s, and
  every uniform it declares must be one the pass binds (`PipelineBuilder`:
  "Unable to find shader defined uniform"): its inputs' `<name>Sampler`s,
  `SamplerInfo`, its own uniform blocks and `Globals`. The post-effect JSON
  (`PostChainConfig`, persistent targets, `LevelTargetBundle.OUTLINE_TARGETS`
  and `MAIN_TARGETS`) and the game's own `entity_outline.json` and `blur.json`
  did not change, so each chain is one file at the pack root and only its
  shaders come in two dialects. `glslangValidator -V --amb` checks the 26.3
  form offline the way shaderc does (`-S frag` without `-V` the 26.2 one).
- **The pack replaces `text.fsh`/`text.vsh` and `position_tex_color`**,
  `post_effect/entity_outline.json` and `post_effect/blur.json`, and in its
  26.2 overlay `rendertype_clouds.vsh` (overlays and menus, below). So do other plugins
  that draw effects through the text shader. Two packs that both replace
  `text.fsh` (or a post chain) on one client cannot both work: whichever is
  applied last wins and the other's feature silently draws nothing. Nothing
  composes them yet.
- **The pointer leaves the frame under the menu blur, and only there**
  (`Shaders.menuBlurChain`, 26.2 and 26.3). A vanilla screen frees the mouse and the
  system cursor appears, but the pointer is drawn in the level pass, before
  the GUI, and nothing the level draws knows a screen is open: `Globals` has no
  such flag (`MenuBlurRadius` is the option, sent every frame), and in
  multiplayer the level renders the same and the server is sent nothing for
  chat, pause or the inventory (`Gui.setScreen` only frees the mouse and
  releases keys; the inventory key sends a packet only when riding a
  `HasCustomInventoryScreen`, `Minecraft.handleKeybinds`). After the level only
  the GUI draws, and its shaders cannot read the frame: the one pass that can is
  `post_effect/blur.json`, which `GuiRenderer.draw` runs over the main target when
  a screen asks for a blurred background (`Screen.extractBlurredBackground`:
  the pause menu, the option screens, with the blur option at 1 or more). Chat
  draws no background, containers (`isInGameUi`) a gradient, and blur 0 none:
  nothing can hide it for those. Depth would mark the pointer, but the game
  clears it before the GUI, and alpha cannot (blending over opaque gives 1). So
  `text.fsh` gives every opaque pointer fragment a code in the low two
  bits of each channel, a hash of its window pixel (`mcb_code`, at most 3/255
  off), and the chain's first three passes find pixels whose code matches with
  at least two coded neighbours, grow that by two pixels and fill it from the
  nearest kept pixels on each side, before the game's six box-blur passes. A
  natural picture matches in about 1 pixel in 10,000 (tested on real frames),
  refilled from beside it and then blurred. Riding a packet-only horse would
  make the inventory key send a packet instead of opening the screen, but a
  passenger's view yaw follows `yHeadRot`, a tick behind (the pointer lag the
  held seat removed).
- **"Seated" is a camera position, not a uniform.** The view's camera sits at
  k/16 + 3/128 of a block along the picture's normal and on a 0/0.5 grid on the
  other axes; an overlay's at k/16 + 3/128 on all three (`mcb_flag_axis` 1), so
  it is the player's own eye moved at most 1/32 of a block on each. The shaders
  read that back from `Globals.CameraOffset`. That flag is what hides the HUD
  and draws the view in front of the world, for that player only.
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
- **Transparent is map colour 0** (`Canvas.transparency`): a pixel under half
  alpha is kept as YCbCr (0, 0, 0), which no RGB colour converts to
  (`Palette.isClear`), and sent as colour 0, which the client draws as
  nothing: a whole 2×2 block in HI when two or more of its pixels are. A
  transparent area is the same bytes from one drawing to the next, so it is
  never sent again. A view's tint travels in its maps (the 2×2 block after its
  size, `Palette.markTint`) and `text.fsh` blends it over the world.
- **A map's top-left blocks are data, not picture** (the marker, a view's size
  and place, its tint), so the shader paints them from
  a neighbour: the block right of the data when an edge runs between the top
  two block rows just past it (`mcb_fill_right`, worked out once per map in
  `text.vsh`), else each the block below it. Always copying the block to the
  right (the old rule) drew a dash wherever a vertical line crossed a map's top
  edge: the "faint dots" on overlay pages. Measured on the market pages' maps,
  the choice cuts fills that differ visibly from the picture they cover from
  13 % (always right) or 8 % (always below) to 4–5 % on overlays, and from 5 %
  to 3 % on opaque pages. Anything that puts more data in the top row must
  widen `mcb_info` to match.
- **The text shaders are the hot path of every frame with a view open**, and
  every bit of text in the game runs through them too. `text.vsh` does each
  map's fixed work once per vertex (four a map): which of ours it is
  (`mcb_screen_map`), the fill choice above and the view's tint, handed on as
  flat varyings (`mcbMapInfo`, `mcbViewTint`). `text.fsh` only decodes: at most
  2×2 blocks under a pixel's footprint, four texel reads and four lookups a
  block, and a symbol is one read of a 4,096-slot perfect hash packed four to a
  uint (`SymbolHash`). Ordinary text costs one flat compare more than vanilla.
  Measured on Josh's PC (720p, uncapped): world alone 0.53 ms a frame; the
  market at 16×9 2.5 ms, of which about 1.3 ms is the 144 maps' draw calls (a
  map is its own texture, so its own render pass: nothing the pack does can
  batch them) and about 0.4 ms the decode (about 0.7 ms at 1080p). Map count
  is the lever (see the README's "Frame rate"). Moving the per-map work to the
  vertex shader and the one-read lookup measured within noise of the old shader
  at 720p and 1080p (two rounds each); keeping that work per pixel in the
  fragment shader was the slowest variant at 1080p (284 FPS against 310).
- **An overlay keeps the world it opened on** (`ViewOptions.overlay`, every
  `ui:overlay` page; 26.2 and 26.3). Its camera is the player's own eye where it was
  (`Views.overlayEye`, their rotation untouched) and its pointer the client's,
  at frame rate. Only the tiles with something opaque in them are sent and
  framed (`Canvas.Snapshot.opaque`, `MapWall.sendOnly`; grow-only, new frames
  once their maps are delivered), placed in the window by their tile like any
  client-placed view (`HI_TILED`), opaque or not drawn, never blended with the
  world. What does the rest is the pack's `post_effect/entity_outline.json`
  (`Shaders.overlayChain`): the game runs it whenever a glowing entity was
  drawn (`LevelRenderer.render`, right after the main pass), whatever the camera, and
  may write `minecraft:main` (`LevelTargetBundle.OUTLINE_TARGETS`). The switch
  is a glowing block display at the eye, a speck that draws nothing anybody
  sees and is never culled (a display with no width is not). An extra map,
  the signature (tile 239, 239; `Palette.markSignature`), goes over the
  window's four bottom-left pixels and `text.fsh` writes there a check, the
  tint, the flags (blur, kept, reversed depth) and a second check. The chain
  keeps the game's four outline passes, then: while the overlay opens
  (`Views.take`: no panel yet) it copies the world, blurred if asked, into a
  persistent target every frame; once kept (`Views.keep`: the flag flipped and
  the player turned to the picture's axis in one bundle, then the panel and
  pointer) it replaces everything not at the view's own depth (the panel at
  0.99999, pointer and signature at 1) by that picture under the tint, and
  clears the outline target (another glowing entity's outline would be drawn
  where it is now). A new window size drops the persistent target (alpha 0),
  and it is taken again from the frame then. 26.2 draws clouds, weather and
  particles after the chain, so its `rendertype_clouds.vsh` draws no clouds for
  an overlay's camera (and under Fabulous, water and particles stay live). 26.3
  draws all of the world in its main pass (clouds, weather, improved
  transparency's blend included) before the chain, so they are in the kept
  picture and the pack leaves 26.3's clouds alone. Measured on a 26.3 client
  (classic and improved transparency): opening moves the world 0 px, a 6°
  turn changes no pixel outside the panel, the pointer shows in about 1 ms.
  Without the signature every pass of ours discards: nobody else's game
  changes. With no room to hold the player, `views.client-pointer` off, or a
  client version the chain was never seen to run in (`Shaders.Dialect.of` the
  protocol `net/ClientVersion` reads: ViaVersion's word where it is installed,
  else the handshake's), an overlay falls back to the server pointer with a
  mannequin camera (the world live round it); without the pack, to a wall with
  the world showing through. A client that loads the pack but not the chain
  would otherwise see the world turn with the pointer (main's jar on 26.3 did:
  opening the market overlay swung the world about 115 px, and moving the
  pointer changed a quarter of the window round the panel). A new dialect adds its
  protocol to `Dialect` only once a real client of it has passed the checks.
  The frozen world is the price of the frame-rate pointer: with the camera on
  another entity the local player is never drawn (`LevelExtractor` skips it
  unless it is the camera), and the only thing still drawn from its rotation
  is the hand's sway, a difference with no absolute yaw
  (`ItemInHandRenderer.submitHandsWithItems`).
- **Hover layers: the client switches to a hover by itself** (`views.hover-layers`,
  off by default; `api/HoverLayers`, `world/LayerSet`, `ClientHovers`). Each
  hover drawn ahead near the pointer is handed to the client as hidden maps of
  the 2×2 blocks it changes, map kind 7 in the text shaders: top markers swapped
  (`Palette.markLayer`), then the view's size, the canvas pixel the map's third
  row starts at (a layer map lies anywhere, 128 × 126 of the canvas, not on a
  tile: half the maps of tile-aligned ones for a row of buttons), the pointer
  speed, priority and side (x or z, which way), and the rectangle where the
  pointer must be. `text.vsh` works the pointer out as `mcb_client_pointer`
  does (`mcb_hot`) and puts the map in the window only while the pointer is
  inside, priority + 1 in `mcbMapInfo.y`; otherwise behind the far plane.
  `text.fsh` draws it all or nothing at 0.99999 + (priority + 1) × 1.2e-7 (still
  inside the overlay mask's > 0.99995), never its top two rows. The server then
  hears the pointer, finds the client already shows that hover
  (`ClientHovers.shows`) and draws nothing; a layer the pointer is in but should
  not be is taken off. Every picture change clears the layers, in the same
  sender pass (layers are assigned after the view's own tiles).
  - **A frame costs the client a draw call every frame, even one whose quad the
    shader throws away** (about 9 µs; FPS's measurement). So a layer map only
    has a frame while a layer holds it: `Views.frameLayers` spawns and removes
    them per map (`MapWall.frameOnly`) once the maps as they are now are
    delivered, so a new frame never shows the map item and a removed one was
    already marked to show nothing. Nothing armed: no extra frames at all.
  - A layer needing more than half the maps is never armed (market's slot
    hovers change the whole detail panel: 20–38 maps each). Layers are worked
    out once per picture (`ClientHovers.worked`) and re-armed from that after
    giving way for room.
  - Measured on Josh's PC (120 FPS cap, 32 maps, pixel-timed, layers off and
    on interleaved, two sessions): quests hover 34–38 → 8 ms median (one
    frame), p90 50–58 → 8; crates 50–54 → 8 ms (p90 84–101 → 50); leaving both
    one frame. The market got no better and somewhat worse on entering (median
    42 → 54 ms in the second session): its slot hovers are refused, and arming
    its small targets competes with its pictures. Frame rate, uncapped: nothing
    armed, no extra frames (market resting 352 against 355 FPS); quests resting
    with 20 layer maps armed 375 → 348 FPS (about 10 µs a map, as predicted).
    Server CPU per target (`gradle layerBench`, VPS) rose on quests (51 → 75 ms)
    and market (135 → 190 ms) and fell on crates (85 → 69 ms): arming encodes
    about 7 maps per layer, about 4 layers again after each picture change. Not
    done: sharing encoded layers across players (SharedRepaints keys would do).
    Rig: `rigs/r4/InstantHover/ih_pc.py`; raw results there.
- **The mouse skips the tick.** `Pipeline.watch` reads a seated player's
  rotation packets on the netty thread as they arrive, and the pointer's move
  is written straight back (`Pipeline.sendNow`); the server's own position
  updates for the pointer are dropped for that player, or one worked out a
  moment earlier would pull it back. So the pointer is spawned for them already
  in place (`Views.reveal`). The client sends its rotation once per client tick
  (20 a second, whatever the server's tick rate): raising the server's TPS
  would not make it send more often.
- **Pictures skip the tick too.** `MapSender` runs on its own thread
  (`MCGUI-send`). It wakes when a canvas publishes a drawing, when who sees
  what changes, or when a write lands; every 8 ms only while a picture is
  still part sent (out of budget, or the connection full), and otherwise it
  sleeps. It writes each player's patches straight to their channel with one
  flush (`Nms.writeNow`). `Connection.send` would not do: off the server
  thread it queues the packet until the next tick. `writeNow` drops the write
  while the player is being reconfigured (`isAcceptingMessages()` is false),
  when a game packet would fail to encode, and the tile is sent again later.
  Who sees which canvas is still worked out once a tick on the server thread,
  and handed to the sender only when it changed.
  The per-player budget (`screens.max-bytes-per-second-per-player`) is spent in
  bytes on the wire: a patch is taken from it at its map bytes times the ratio
  that player's patches have compressed at (`MapSender.estimate`), then settled
  at its real compressed size where `Wire.wireBytes` knows it (`SharedCompression`
  made it) (`MapSender.charge`). Counting map bytes held a moving arcade game of
  scaled-up pixel art (1.9 MB of map bytes a frame, 136 KB compressed) to 8
  frames a second. The in-flight window stays in map bytes (800 KB).
  Pointer reports reach `ViewContent.pointer` through
  `UIFramework.serverThread()`, the server's own task queue, which Paper
  drains while it waits for the next tick: still the server thread, but not up
  to 50 ms late.
- **One pool for all CPU-heavy work: `api/Cpu`.** `cpu-threads` (default: the cores
  minus one) is the most threads that convert, encode or draw at once, for this
  plugin and any plugin on top of it (MC GUI's render jobs and painter bands run
  on it too). Never make another pool for work like that: pools add up, the sum
  passes the core count and the server thread waits for a core (Java priorities do
  nothing on Linux). Work on the pool may wait for work it forks (a pool thread
  that waits runs queued work, or hands its place to a spare, so a wait cannot use
  the budget up), but must not wait on the network or on the server thread.
  `MapSender` (one thread of its own), the pack's web server and netty are not
  part of the budget, but the sender compresses one pass's map patches for a
  player on the pool too (`SharedCompression.patches`, each thread with a
  compressor of its own): one at a time, a hover changing 24 tiles took 10 to
  16 ms on the sender's thread.
- **`Canvas.draw` converts on every core**, in bands of 32 rows on that
  pool. It reads `INT_RGB`, `INT_ARGB` and `INT_ARGB_PRE` rasters in place,
  sub-images included; any other type goes through `getRGB`, which is several
  times slower. A row the same as the one above is not converted again, and runs
  of one colour are converted once (a picture drawn small and scaled up).
  `draw(image, x, y, areas)` converts only those areas and publishes them as one
  change: the arcade's runner hands over only the tiles that differ from the last
  frame. Only HI tiles are made when a picture is drawn: a PLAIN tile is
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
- **Pointer speed is per mouse count, and the count is read off the pitch.**
  `MouseSteps` keeps each online player's last changes of pitch (sampled every
  tick in `Views.learnMice`) and finds the step every one of them is a whole
  multiple of: the client turns by whole counts times a step that depends only
  on the sensitivity. A view's pixels per degree are `views.pointer-speed` (and
  `/mcgui pointer`) per count over that step, and reach a client-placed pointer's
  shader as the pointer text display's distance from the eye
  (`Shaders.clientPointerDistance`). A turn the server makes is not whole
  counts: teleports and respawns, and every rotation a view sends (the seat,
  the turn back from an edge, a level head, `/mcgui debug point`), bump
  `ViewImpl.serverTurns` or call `MouseSteps.hold`, which leaves the next few
  samples out. Anything new that rotates a player must do the same. Keyboard
  input never rotates anybody.
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
- **Permissions are one node per subcommand** (`mcgui.<sub>`, plus
  `.others` when the command names another player), checked in
  `FrameworkCommand` through `permissionFor`. `mcgui.admin` is a parent
  in `plugin.yml` that grants them all and is never checked itself. A new
  subcommand needs its node in `plugin.yml`, under the parent too, and in
  `SUBCOMMANDS` so the check and tab completion see it.

## The framework: testing

`/mcgui test wall` and `/mcgui test view` need nothing else installed.
`/mcgui debug <player> point|click|scroll|speed|close` drives a view from the
console, which is how a harness tests one without anybody at the mouse. The
shaders can only be judged in the real client.
