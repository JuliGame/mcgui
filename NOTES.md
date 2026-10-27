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
| top level | The plugin: `MCGUIPlugin` and the `MCGUI` API, `PageStore` (the folder, live reload), `Session` (one player's page on one screen) and what it draws with (`FirstPictures`, `AnimFrames`, `ClientHovers`, `Renders`, `Forms`, `Keys`), `Screens` (walls and views), `Placeholders`, `Sounds`, `Heads`, `UiCommand` with `Stats` and `PageImport` |
| `chat` | `Chat`: the plugin's messages in its palette (`chat:` in config.yml) |
| `event` | `PageClickEvent`, `PageOpenEvent`, `PageCloseEvent`: the Bukkit events other plugins listen for |
| `framework` | The pictures on walls and in views that pages are drawn through, and other plugins' API; its own notes are below |

The top level stays one package on purpose. Its classes are package-private and lean on each other's
package-private members (`Session` on `FirstPictures`, `AnimFrames` and `Forms`; `Screens` on `Session`;
the command, `Stats` and `PageImport` on `MCGUIPlugin`'s accessors), and the tests sit in the same package.
Splitting it into `command`, `session` and `page` would make all of that public, `MCGUIPlugin`'s accessors
included, which other plugins can see.

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
  size, transparency) among every live tile, so players' views of one page publish one `Tile` (its HI, HI_TILED,
  PLAIN and colours) wherever they look alike, and a viewer who has it is sent nothing. A shared map is
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

**A client that never shows its window** (2026-10-27), so a test can run while
somebody uses the PC: a game window takes the foreground once as it is
created. A `-javaagent` that rewrites the calls hides it for good, and the
game renders and screenshots as usual (snap the main target from a probe
agent; join with `ConnectScreen.startConnecting`, not portablemc's `-s`,
which stops at the pack prompt). GLFW clients (1.21.11 to 26.2):
`glfwDefaultWindowHints` also sets VISIBLE/FOCUSED/FOCUS_ON_SHOW false and
`glfwShowWindow`/`FocusWindow`/`RequestWindowAttention` do nothing; 26.3 is
SDL: `SDL_WINDOW_HIDDEN` or-ed into `SDL_CreateWindow`'s flags,
`SDL_Show`/`Raise`/`Restore`/`FlashWindow` do nothing. Both need Java 24+
(`java.lang.classfile`; portablemc `--jvm` the launcher's Java 25 for
1.21.11). Checked with a windowless foreground logger on every version: the
foreground never left the user's own window. The rig, agents and results of
the improved-transparency fix are in `rigs/ovl/` (`measure.py`).

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
| `pack/` | `Shaders` (both GLSL dialects), `SymbolHash` (the shader's colour lookup), `ResourcePack` (build, upload or serve, who has it; the upload is plugin-common's `host/PackUploader`, shared with RP Engine, Dialog Engine and 3D Engine) |
| `net/` | `Nms` (every server internal, by reflection), `MapSender` (diffed map patches, per-player budget, frame re-pointing), `Wire`/`ServerWire` (what the sender writes through), `Pipeline` (ping answers, the hidden inventory, each connection's frame pointing) |
| `world/` | `MapWall` (the frames), `MapPool` (placed walls whose frames share maps), `Walls` (placed walls and crosshair input), `Views` (seating, camera, pointer, input), `PrivateView` (entities and placed walls in a seated player's way, hidden from them) |

## Spigot and Paper: one jar

MC GUI runs on Spigot and Paper from the same jar (since 0.3.0; SpigotMC lists only what runs on Spigot). How:

- **It compiles against spigot-api**, so a Paper-only call does not build. Paper implements Spigot's API, so what
  compiles runs on both.
- **Adventure is the plugin's own**, shaded and relocated to `ai.resourcepack.ui.libs.kyori`: Spigot ships none,
  and Paper's must never meet ours. No Adventure type crosses into the server. Chat leaves as BungeeCord components
  (`Platform.send`, `actionBar`: Adventure to JSON to `ComponentSerializer`), the console gets section-sign colour
  codes, and a text display's text (the pointer glyph, which names the pack's font) is set as the game's own
  component from JSON on the entity behind it, because Spigot's `setText` takes a legacy string with no font.
  Nothing in the public API (`MCGUI`, `UIFramework`, `event/`) takes or returns an Adventure type, and must not
  start to: it would be our relocated copy, which no other plugin can name.
- **`framework/platform/Platform` is every other difference**, each decided by what the server has rather than by
  asking which it is: the tick (`Bukkit.getCurrentTick()` on Paper, `MinecraftServer.getTickCount()` on Spigot),
  the command map, a player's protocol version (Paper keeps the handshake's; Spigot admits only its own version),
  a player's profile (called by name, since Paper's `getPlayerProfile()` returns its own type), collisions (Paper's
  `hasCollisionsIn`, else the blocks' collision shapes and the border), and transient attribute modifiers (Spigot
  saves every modifier, so `Views.onJoin` takes the seat's off).
- **A right click on a wall's frame is read off the wire on both** (`Pipeline.useEntities`,
  `ServerboundInteractPacket`): the frames exist only on clients, Paper reports such a click with
  `PlayerUseUnknownEntityEvent` and Spigot with nothing. Left clicks were always the swing.
- **Entity ids for client-only frames** come from Paper's `UnsafeValues.nextEntityId`, or on Spigot from the
  level's own counter (`Level.getNextEntityId`).
- **Shared compression is Paper's only**: it uses the Velocity natives Paper bundles. On Spigot each connection
  compresses its own map packets, as with shared compression off.
- **Testing**: the mineflayer bot cannot join 26.3 (protocol 777), even through ViaBackwards (it is kicked for
  an invalid move). On 26.2 with ViaVersion and ViaBackwards it joins as 26.1, so 26.2 is where a bot can drive
  both servers; 26.3 needs a real client.

## Folia

The same jar runs on Folia (since 0.5.0; `folia-supported: true` in `plugin.yml`). Folia has no main thread: each
region of the world ticks on its own, its `BukkitScheduler` refuses every task, and an entity may only be changed
(anything that reaches CraftBukkit's `getHandle()`) or plainly `teleport`ed on the thread of the region it is in.
Reading a position, a world or a UUID is fine from anywhere. How MC GUI copes, all in `framework/platform/Threads`:

- **Every task goes through `Threads`** (on plugin-common's `scheduler/Tasks`, which reaches Folia's schedulers by
  reflection, since we compile against spigot-api). Off Folia each call is exactly the Bukkit scheduler call it
  replaced, so Spigot and Paper behave as before. A new `Bukkit.getScheduler()` call is a Folia bug.
- **One lock.** All of MC GUI was written for one thread, so on Folia every way in (a task, an event through
  `Threads.register`, a command) holds one `ReentrantLock`, and MC GUI still sees one thread at a time. Netty threads
  never take it; they hand work back as before. Its own work per tick is small, so regions seldom wait on it.
- **The tick is split.** What concerns nobody in particular (sending maps, walls' viewers, walls' sessions) runs on
  the global region; what concerns one player runs on their thread every tick (`Threads.PlayerTicks`): their view
  (`Views.tick(Player)`), what they aim at on a wall (`Walls.tick(Player)`) and their seated page
  (`Screens.tick(Player, long)`). Off Folia the old loops do all of it, in the old order. Folia has no server-wide
  tick count, so the global tick counts (`Threads.countTick`) and `Platform.tick` reads that.
- **Work about a player goes to their thread**: a view's and a player session's `serverThread` is the player's
  (`Threads.player`), `Views.close` and `Screens.open` hop there when called from elsewhere (the console's
  `/mcgui open <page> <player>`), a page's `data-command` runs there and a console command on the global region.
  `UIFramework.openView` throws on Folia from any other thread; `MCGUI.open` does the hop itself.
- **Teleports are `Platform.teleport`**: `teleportAsync` on Folia, so a server-placed pointer's mount waits for it.
- **Shutting down**, the thread disabling plugins has no world to work in: an open view is only let go of, and the
  player is put back on their next join from the PDC, as after a crash (`Views.onJoin`).
- **Testing**: Folia 1.21.11 from `fill.papermc.io` (project `folia`) swapped in for a test server's `paper.jar`.
  Checked with bots: start, commands, a page opened by a player and by the console, clicks, chat, getting up, walls
  (frames, aim, clicks), a seated player hidden from another, quitting while seated, reload, import, a shutdown with
  somebody seated, a placed wall coming back. Then by the owner in a real 1.21.11 client (2026-10-25): fullscreen
  pages with the client-placed pointer, overlays and walls all work.

## The framework: things that are not obvious

- **The pack is uploaded by default** (`resource-pack.mode: upload`, 0.5.3): to ResourcePack AI's pack host, the
  `/h/` routes of packs.resourcepack.ai, exactly as RP Engine's `host.mode: upload` does, so a
  change to those routes is a change to both plugins. `serve` needed a second open port and a typed-in address,
  and a blank `public-url` sent players `127.0.0.1`: the commonest reason a player had no pack. A failed upload
  falls back to `serve` until the six-hourly retry works; that touch is also what keeps the pack on the host.
  A config written before 0.5.3 still says `serve`, so `serve` with a blank `public-url` warns at startup.
- **A view needs the pack, and there is no fallback (owner's instruction, 2026-10-26).** A view's picture hangs
  wherever it fills the player's view, often inside the ground, and only the pack's shaders draw it in front of the
  world. Without the pack, `PrivateView` used to tell the player alone that every block between their eyes and the
  picture was air, with a server-drawn pointer in plain map colours: to a player whose pack had failed to download
  (a blank `public-url`, which only works on the server's own machine), `/mcgui open` sat them down and cleared the
  area round them. That whole path is gone. `Framework.openView` refuses instead and offers the pack again, so the
  reason reaches whoever opened it. Walls still work without the pack, in plain colours. `external` with a blank
  `public-url` means the pack is merged into another plugin's, which sends it under its own id, so any pack a player
  loads marks them as having ours (`ResourcePack.mergedElsewhere`); before, those players always got the fallback.

- **The server internals are reached by reflection**, so the plugin compiles against the plain 1.21.8 API and needs
  no paperweight. Every member is looked up once in `Nms`'s constructor; a
  missing one disables the plugin at startup with the reason. They were checked
  against Spigot and Paper 26.2 and 26.3, which agree on all of them: both run
  Mojang's names. **Paper makes many of the game's members public and Spigot does
  not**, so every lookup goes through `net/Reflect`, which tries the public member
  and then opens the declared one. A bare `getField`/`getMethod`/`findConstructor`
  on a game class works on Paper and fails on Spigot at startup.
- **1.21.11 (2026-10-25).** Paper there runs Mojang's names too, and only
  a few members differ (`ServerboundInteractPacket` is a class with a private
  `entityId` and says its hand only to a `Handler`; one entity id counter,
  `Entity.ENTITY_COUNTER`; `HandlerNames` inlined away, so its strings are written
  in). **Spigot before 26.1 runs Spigot's class names and Mojang's obfuscated member
  names**, so `net/Names` reads `src/main/resources/spigot-names/<version>.txt` and
  `Reflect` asks it each class's own name for a member. A table is written by
  `java tools/SpigotNames.java <maps-mojang.txt> <maps-spigot.csrg>
  tools/spigot-names.classes <out>` from the mappings `BuildTools --rev <v>
  --remapped` leaves in `~/.m2/repository/org/spigotmc/minecraft-server/`. **A new
  game class looked up by name goes in `tools/spigot-names.classes` and every table
  is written again**, or it works on Paper and fails on Spigot 1.21.x at startup.
  Checked in real clients by the owner: walls, fullscreen pages and overlays (blur
  too) with the client-placed pointer on Paper and Spigot 1.21.11 and 26.1.2.
  **Spigot spawns an entity without the spawn location's rotation** (Paper keeps it):
  set it in the spawn consumer, or a client-placed pointer faces south and is drawn
  stretched as the camera turns. **1.21.11 is the floor, and `Framework.enable` refuses anything older**:
  1.21.9-1.21.10 have no `CameraBlockPos`/`CameraOffset` in `Globals`, so their
  shaders cannot tell a seated camera, and the fallback (the view as a client
  without the pack sees it, the seat's box round it) was seen in a 1.21.10 client
  and is not shippable. The same code ran on Paper 1.21.9 and Spigot 1.21.10
  (`git log` has the 1.21.9 dialect and the 1.21.10 name table) if that changes.
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
- **Before 26.2** (2026-10-25) there are two more overlays, `uiengine_26_1`
  (84-87) and `uiengine_1_21_11` (75-83), each a full set replacing the root's. The text shader is `rendertype_text` there, ONE shader
  for GUI and world text (26.2 compiles `text` per `IS_GUI`), so `Shaders.presplit`
  turns each `#ifdef`/`#ifndef IS_GUI` inside a function into a run-time test of
  the projection (`mcb_gui()`, orthographic) and decides every other conditional
  with nothing defined; the GUI path writes depth and zero fog distances for that
  reason. 1.21.11 has no `sample_lightmap.glsl`. Every shader of every overlay was
  compiled with glslang against that version's own includes from its client jar,
  and a 1.21.11 client loaded the pack without a shader error and drew walls and
  fullscreen pages as 26.x does (seen by the owner), as did 26.1.
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
- **F5 must leave a view alone, and does** (2026-10-26). The first press pulls
  the camera back along its look by the `camera_distance` attribute (4 blocks;
  `Camera.alignWithEntity`), off the flag: looking dead at the centre that is a
  whole number of blocks along the axis and the flag still matches, so a quick
  check passes, but with the pointer anywhere else the view drops to a loose
  wall of maps and the HUD returns. `Views.hold` (and `fixDistance` on the
  mannequin) zero it under the seated key; Spigot saves modifiers, so it is in
  `HELD` for `onJoin` too. The second press turns the camera exactly round (yaw
  half round, pitch negated: the look negated). For a client-placed pointer no
  shader can tell that from the player really turning past 90°, which is
  ordinary (the picture's edge is ~144° at default sensitivity), so the server
  tells it: `Views.sendTurnHint` puts the player's turn, to a 64th of a turn,
  in the pointer's text opacity (one entity-data packet from the netty thread
  when the sector changes; `Shaders.turnHint`), and `mcb_hot` takes whichever of
  the look and its negation is within a quarter turn of it. A hint that far out
  would need a quarter turn inside one round trip. Hover layers get a quarter
  turn in each held map (`LayerSet.hint`, the symbol under its y). A mannequin
  camera is level, so the turn is exactly half round about the vertical: its
  frames lie in its block like a client-placed view's (never culled), and the
  server's pointer, `FIXED` square to the axis (a billboard would face the turned
  camera), is turned back in the shader when it is behind the camera.
  `Nms.teleport` keeps rotation for it. Measured on the PC rig (2026-10-26),
  each client on a server of its own version, Paper, Spigot and Folia, 1.21.11
  to 26.3: the pointer within 8 px at seven points (turns to ±127°, pitch to
  ±64°) in all three perspectives, fullscreen and overlay, client and server
  pointer, hover layers lighting only under it; and through a proxy holding
  every byte 100 and 250 ms each way, turning 120° in 2 s to 150 ms.
- **A view fills the window because `text.vsh` puts it there.** Every map
  carries the view's columns and rows in the two pixels right of the marker and
  its own column and row below them (`Canvas.Encoding.HI_TILED`), and goes to
  its place in the window from those, wherever its frame is: all of a view's
  frames lie in the camera's block (`MapWall.Stack`), on its floor or, when the
  camera is in the block's upper half, its ceiling, so the camera is inside each
  frame's culling box (the box grown half a block) and no turn can cull them.
  Always on the floor, an overlay (the standing eye is 0.65 up its block) lost
  every map, and with them its tint and kept world, in a band of looking up
  round 36°, and F5's front view looking down as much. The server's pointer is
  text whose red is `Shaders.POINTER_RED` with the columns and rows in green and
  blue; while seated its vertices go to their distance from the camera's axis,
  in blocks, over half the picture's size: the window edge to edge, at any FOV
  or window shape. It sits ON the picture's plane (the wall is still placed to
  fill 60% of the player's view, `Views.STRETCHED_FIT`, which `PrivateView`
  reads too), and both pointers are as near as depth goes.
- **ImmediatelyFast's map atlas** (2026-10-27). The client mod ImmediatelyFast (Fabric and NeoForge, every
  version MC GUI supports: 1.14.x for 1.21.11 to 1.17.x for 26.3) copies every map into a 2048 × 2048 texture
  by default (`map_atlas_generation`, 16 × 16 maps a texture, more textures as needed) and draws each frame's map
  from its square of it, the quad's texture coordinates that square's. The shaders took "Sampler0 is 128 × 128"
  to mean "a map", so with the mod every wall showed its raw palette code as noise and a view or overlay drew
  nothing but a strip of noise on the camera block's ceiling (seen on Fabric 26.2 with ImmediatelyFast 1.16.5, and
  on 1.21.11 with 1.14.3; `map_atlas_generation: false` alone put it right). Now every map read goes through
  `mcbBase`, where the map starts in Sampler0: `mcb_texture` tells a map's own texture (1) from one that may hold
  many (2: square, a multiple of 128 from 256 up, so a font page too, which its texture coordinates or the marker
  then turn away). A quad corner there is a corner of up to four maps, and the vertex id is offset by whatever
  was batched before it, so `mcb_locate` finds a view's map by which corner of the camera's block the vertex is
  (its frames all lie there, `MapWall.Stack`: u along x, v along z on the floor and against it on the ceiling);
  any other map of ours in such a texture (a wall's) is -1, and `text.fsh` takes the 128 square the pixel is in,
  per pixel (the marker read every pixel, the fill only near it). ImmediatelyFast 1.16.2+ (26.2) and 1.17 (26.3)
  would also turn the atlas off for a pack replacing `core/text` whose pack.mcmeta says
  `"immediatelyfast": {"incompatible_features": ["map_atlas_generation"]}`, but 1.21.11's and 26.1's have no
  such switch and a pack merged into another plugin's loses its pack.mcmeta, so the pack does not ask. The log
  line every player of it gets, font atlas resizing turned off for the server pack, is its default for any pack
  replacing `core/text` and costs MC GUI nothing. Seen right with the mod on in Fabric clients: 26.2 (walls,
  fullscreen, overlays, hover layers, F5, the server pointer, frames on the floor and the ceiling), 26.1.2 and
  1.21.11 (walls, fullscreen, overlays); 26.3 only compiled with glslang. Rig:
  `rigs/ifast/` (`trial.sh` restarts the Fabric client with the mod in or out and any of its config,
  `scene.sh` takes a wall, a fullscreen page and an overlay).
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
  (`Views.take`, sent the moment it opens, while the panel is drawn and sent: no
  panel yet) it copies the world, blurred if asked, into a persistent target every
  frame and leaves the window as the game drew it (no tint yet: only the HUD goes,
  the eye being flagged); once kept (`Views.keep`: the whole signature with the
  kept flag and the picture's tint, the turn to the picture's axis and the panel's
  frames in one bundle, then the pointer) it replaces everything not at the view's
  own depth (the panel at 0.99999, pointer and signature at 1) by that picture
  under the tint, and
  clears the outline target (another glowing entity's outline would be drawn
  where it is now). The keep waits for the take's ping to come back, and for
  100 ms after the client had the signature (`Views.taken`, the round trip
  counting towards it): the client must have drawn a frame with the glowing
  switch before the panel reaches it, or the panel is in the picture taken.
  So an overlay opens two round trips after the command, a fullscreen page
  one (see "A view opens as soon as its picture is out", below). A new window
  size drops the persistent target (alpha 0),
  and it is taken again from the frame then. While the world is being taken
  the compose leaves it as drawn (tint and blur come with the panel), so
  that improved transparency can take it whole (below). Before 26.3 clouds
  are drawn after the chain, so the pack's `rendertype_clouds.vsh` draws none
  for an overlay's camera; 1.21.11 draws particles after it too (its
  `particle.vsh` likewise; 26.1 and 26.2 draw them in the main pass), and
  weather is already clear for anybody at a view (`setPlayerWeather`).
  **Improved transparency (1.21.11's Fabulous) before 26.3** draws
  translucent blocks (water, glass, ice, stained glass), dropped items,
  particles, weather and clouds into targets of their own and blends them in
  with `post_effect/transparency.json`, after the chain: they were missing
  from the kept picture and drawn live at the turning camera, so they moved
  with the pointer while solid blocks stayed (reported 2026-10-27). The pack's
  own `transparency.json` (`Shaders.transparencyChain`, in the three
  pre-26.3 overlays only: 26.3 has no such effect) runs the game's composite
  into `final`, then the same keeping passes on `final` with a persistent
  target of its own, the game's blit, and the compose. It cannot see the
  outline chain's targets (`LevelTargetBundle.SORTING_TARGETS`), and the
  signature in main is already covered with world by then, so the outline
  compose hands the signature on in the alpha of main's twelve bottom-left
  pixels (a byte each; main's alpha is never shown, and screenshots come out
  opaque) and `overlay_relay` draws it back over `final`'s four. Measured on
  the PC (2026-10-27, each client on a Paper server of its own version, a
  page with a small panel in front of leaves, ice, water, stained glass,
  glass and stone, the pointer moved three times): pixels outside the panel
  and pointer that changed, with improved transparency, went from 39–44k
  (26.2, 26.1.2) and 100–131k (1.21.11) to 0, translucent blocks from
  63–86% of their pixels to 0, solid blocks 0 throughout; without it 0 stays
  0, and 1.21.11's 4.7–5.9k (particles) went to 0; 26.3 was 0 before and
  after in both modes; fullscreen pages came out identical to the pixel. 26.3
  draws all of the world in its main pass (clouds, weather, improved
  transparency's blend included) before the chain, so they are in the kept
  picture and the pack leaves 26.3's clouds alone. Measured on a 26.3 client
  (classic and improved transparency): opening moves the world 0 px, a 6°
  turn changes no pixel outside the panel, the pointer shows in about 1 ms.
  Without the signature no pass of ours draws a pixel: nobody else's game
  changes. Each of our passes has its own vertex shader (`overlayTakeVsh`,
  `overlayBlurVsh`, `overlaySignedVsh`) that reads the signature (and whether a
  picture is being taken) and gives the game's window-sized triangle only what
  the pass writes: the window, the one-pixel "not taking" mark the take passes
  hand on, or nothing. A fragment that discards still costs: the six take
  passes over the window were about 0.2 ms of a still overlay's 3.4 ms at 1080p.
  With no room to hold the player, `views.client-pointer` off, or a
  client version the chain was never seen to run in (`Shaders.Dialect.of` the
  protocol `net/ClientVersion` reads: ViaVersion's word where it is installed,
  else the handshake's), an overlay falls back to the server pointer with a
  mannequin camera (the world live round it). A client that loads the pack but not the chain
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
    hovers change the whole detail panel: 20–38 maps each). Arming runs after a
    drawing ahead, on its job, and stops between layers (and between maps) the
    moment the session asks for a picture or any work waits for a pool thread
    (`Renders.queued`); it never holds `ClientHovers`' lock while it works, and
    its CPU comes out of the drawing-ahead budget. What a layer comes to is
    kept by its repaint's pixels (`ClientHovers.WORKED`) and its encoded maps by
    a hash of its pixels (`LayerSet.KEPT`, 16 MB), so every player in the same
    state arms it without working it out again, and a map the client still
    holds is sent only its marks. Arming is about 0.1% of the server's CPU.
  - **The picture catches up** (`Session.catchUp`): while the client shows a
    hover by itself the server's picture is of another state. Once the pointer
    rests 100 ms and the pool is idle the server applies the hover (drawn
    ahead, so cheap); nothing is drawn ahead from a picture the client has
    moved past. Without it the next hover the client cannot show was drawn
    from the old state, undoing it too: off the close button then onto the
    trade button was the whole market picture (60 ms on the PC), which was the
    market's "worse with layers".
  - Layer maps start on a column ≡ 0 and a picture row ≡ 2 (mod 4)
    (`LayerSet.places`), so their dither cells and colour blocks are the view's
    and the picture arriving under a layer changes no pixel.
  - Measured on Josh's PC (120 FPS cap, 32 maps, pixel-timed, off and on
    interleaved, 16 trials each, catch-up build): market enter 41.9/59.1 →
    33.3/58.5 ms (p50/p90), leave 59.2/101.4 → 37.7/100.6; quests enter
    33.4/50.7 → 8.3/8.3, leave 42.1/58.3 → 8.3/8.3; crates enter 50.7/92.9 →
    8.3/58.3, leave 20.8/59.2 → 8.3/16.6, but crates' disabled button (its
    hover inside the crate card's) did not reach 90% of its pixels in 2 of 16
    and a second session saw crates leave p90 178 ms: not yet same-or-better
    on every page, so it stays off by default. Server CPU, `bench.py` hover
    cell (fakes sweeping), ABBA twice: 50 players market 0.98×, quests 0.85–
    0.91× of layers off. Frame rate: about 10 µs a held layer map.
    Rigs: `rigs/r4/InstantHover5/ih5_pc.py`, `HoverTimeline` (headless,
    `-Dpages= -Dblocks= -Dper=`); raw results in `rigs/r4/InstantHover5/`.
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
  `UIFramework.serverThread()`, the server's own task queue, which the
  server drains while it waits for the next tick: still the server thread, but not up
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
  rotates the picture and keeps each moved tile's arrays (and the HI_TILED and
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
- **Placed walls share maps: one per distinct tile a player has in them**
  (`MapPool`, `MapSender.pooled`). The client draws every distinct map with a
  draw call of its own each frame (`RenderTypeFeatureRenderer.Group` merges
  only frames that show the same map texture), about 15–20 µs a map at 1080p
  on Josh's PC however small it is on screen, so frames that show the same map
  cost one draw. Canvases share tiles of the same colours (`shareAlike`), so the
  same tile is the same array on any wall; a frame of any placed wall may be
  pointed at any of their maps that holds its tile. Rules: a map more than one
  frame shows is never sent anything (whoever changes moves to a map no frame
  shows, the tile's own first so every player's frames come back to the same
  ids and patches stay compressed once for all of them); a map still waiting in
  a queue is never counted on for what it holds now; a tile on its way to
  another map is waited for (`WAIT`), not sent twice, so walls of one page that
  come into view together cost one wall's bytes. Frames are counted from the
  pointing of every placed wall, in range or not: a client keeps a wall's maps
  after its frames go, and frames shown again are pointed where they were.
  Views keep their own maps. Measured (Josh's PC, 1080p, 48 walls of 4×3 in
  view): one page on every wall 75 → 184 FPS, 48 different pictures 76 → 88
  (their plain areas repeat).
- **Placed walls' frames exist on clients only, and only once pictured**
  (`MapWall.share`, `showFrames`, `hideFrames`). As server entities they were
  tracked out to Paper's `entity-tracking-range.misc` (96 blocks) while
  pictures go only to players within `screens.view-distance` (48), so walls
  beyond it showed the filled map item. Now `Framework.work` brings a wall's
  frames to a player in range once `MapSender.delivered` says its picture is
  out (the frames ride the same connection after the maps, so they never show
  the item), and `Walls.tick` takes them off beyond the view-distance plus
  `Walls.KEEP_BEYOND` (8 blocks; a player with the frames stays in range,
  and keeps getting pictures, until then), from a player whose open view's
  cone reaches them (`Views.hides`, `PrivateView.hides`: what entity hiding
  did for the old frames), and when the wall goes. A client drops them itself
  on quit, world change and respawn (`Walls.forgetFrames`: shown again once back
  in range). A right click on one reaches the server as an entity it does not
  know (`PlayerUseUnknownEntityEvent`, interact and interact-at, taken as one
  click); a left click is the swing, as before. No entity, so no chunk tickets,
  no tracker work and nothing to protect from damage or breaking.
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
  have no saved data, so vanilla never sends anything for them. An overlay's
  maps (tiles, signature, hover layers) take the upper half and a fullscreen
  page's the lower (`Views.open`, where the page fits in half: anything bigger
  starts at the first id and overwrites), so going fullscreen to overlay and
  back leaves each kind's maps on the client: it keeps every map's colours for
  as long as its connection lasts, and `MapSender.held` (kept when a player
  leaves every screen, dropped on `reset`, on another connection or when a
  patch was still in flight) tells the sender what they are, so a page shown
  again costs only what differs.
- **A view opens as soon as its picture is out, not a round trip later**
  (`Views.show`, run from the tick and, between ticks, by `MapSender.onProgress`
  when a write lands). The old open waited for every tile to be written and
  then for a ping to come back (`Pipeline.afterClientCaughtUp`) before it showed
  the view, and an overlay then began taking its world: a second ping and 2
  more ticks, the signature and the glowing switch sent only then and the
  panel only after that. So a command took the command's own round trip plus
  the ping's (two) for a fullscreen page and three and about 100 ms for an
  overlay; and a fullscreen to overlay switch drew the overlay into the maps
  the fullscreen page held, so all of them were sent again. Frames and camera
  sent after the last tile reach the client behind it on the same connection
  and are handled after it, so no ping is needed for the first frame to be
  whole. An overlay's take is sent when it opens, while its panel is drawn and
  sent, so the take's round trip is hidden when the picture is slow and is the
  whole wait when it is not. What is left is the command's own trip there and
  back for any view, and an overlay's second round trip (above).
  Measured on the PC (2026-10-27): a real 26.2 client through `lagproxy.py` (a
  proxy that holds each byte one way for the given time and can cap the rate),
  the market page fullscreen and as an overlay, command to the first frame that
  shows the finished page and keeps showing it (`matrix.py` and `cold.py` in
  `rigs/delay/`: 32 x 18 samples of the framebuffer every frame).
  One-way delay, and 4 Mbit/s down for `/4`; ms, before then after (median of
  three; the first open after joining, a range of two, as one open in the
  before runs took twice as long as its twin):

  | Link | Fullscreen | Overlay | Fullscreen, then overlay | First open after joining: fullscreen | overlay |
  |---|--:|--:|--:|--:|--:|
  | 0 ms | 165 → 36 | 270 → 163 | 270 → 171 | 160–371 → 100–110 | 239–338 → 146–169 |
  | 100 ms | 501 → 256 | 791 → 407 | 825 → 422 | 568–1371 → 279–319 | 867–898 → 412–429 |
  | 250 ms | 1150 → 550 | 1667 → 1054 | 1669 → 1014 | 1189–1191 → 578–598 | 1774–2018 → 1021–1026 |
  | 100 ms, 4 Mbit | 1133 → 254 | 1230 → 431 | 1246 → 431 | 1077–1094 → 792–817 | 1237–1267 → 711–722 |
  | 250 ms, 4 Mbit | 1636 → 592 | 2121 → 1052 | 2144 → 1032 | 1646–1707 → 1126–1127 | 2197–2207 → 1063–1072 |

  The first three columns are a page the client has had before (reopened);
  before the change every open sent the whole picture. A fullscreen page is
  about one round trip plus 50 ms (500 ms is one round trip at 250 ms each
  way), an overlay two round trips plus up to 55 ms, and at 0 ms the overlay's
  100 ms margin shows. The first open after joining is bound by the transfer
  on the slow links (at 100 ms and 4 Mbit/s, 550 ms more than a reopen
  fullscreen, 290 ms more as an overlay). In the fullscreen to overlay
  switch the world is shown plain (no clouds, hand or HUD) for the take's round
  trip between the two pictures; the world behind the overlay is the one that
  was taken (outside the panel the frame before it and the finished overlay
  correlate at 0.9999 with no shift, three strips).
  **`network-compression-threshold=-1` costs a first open about 4 seconds on a
  slow link**: the same cold first opens at 4 Mbit/s (100 and 250 ms one way)
  took 5.0 to 5.3 s fullscreen and 4.4 to 4.8 s as an overlay with it, against
  0.8 and 0.7 s (100 ms) or 1.1 s (250 ms) at the default 256 (a 16x9 page is
  144 maps of 16 KB, 2.4 MB
  before compression). The shared compression cannot help there: it refuses a
  connection with no compressor (`SharedCompression`, "it is not compressed").
  Reopened pages send only what differs, so they do not need it. Not
  measured: a server behind a proxy.
- **The mannequin camera** (1.21.9+) has a player's eye height. An armour stand
  (the fallback) makes the client glide its eye height for most of a second,
  off the flagged position the whole time.
- **The server resends the held item after every cancelled entity click**, which is
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
