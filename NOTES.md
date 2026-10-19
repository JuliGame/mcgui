# UI Engine: notes for whoever works on it

HTML pages in game (2026-10-09), drawn through UI Framework
([ui-framework](https://github.com/resourcepack-ai/ui-framework), built from a
clone beside this one at `../ui-framework`).
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
| top level | The plugin: `PageStore` (the folder, live reload), `Session` (one player's page on one screen), `Screens` (walls and views), `Placeholders`, `UiCommand` |

## Things that are not obvious

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
  session (`Session.render` coalesces), into the session's own UI Framework
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
  fastest and UI Framework's `Canvas` reads in place. A layer with a `filter`
  stays straight `TYPE_INT_ARGB`, because `Filters` edits its pixels as
  straight colour.
- **A full picture is painted in up to four bands at once**, fewer when several pictures
  are painting (the painter threads are split between them: a band costs CPU, see
  `sh gradlew loadBench`) (`HtmlRenderer.paintBands`), each by its own `Painter` into its own image,
  with a margin above and below as deep as the furthest blur can reach.
  The bands are deliberately not clipped: an extra clip changes how Java2D
  rounds overflow clips, by a pixel at their edges. Hit regions come from the
  first band, which paints every box of the page; the other bands make none. Partial (hover) repaints are still
  one painter, worked out by `html/Repaint` (which also grows the painted area by a backdrop's reach and
  keeps only the changed part: `RepaintTest` compares every hover with a fresh picture). `sh gradlew renderBench` times a page's full render, its
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
- **A partial repaint takes in every blurred layer its region meets**
  (`Repaint.region`): a `filter: blur()` layer that the clip cuts would blur
  with a cut edge. A backdrop is painted again with a margin instead (see
  above). A blur that reads past the picture's top or bottom where the page
  goes on means a full picture, which paints the page there.
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
  Small text stays legible because UI Framework's shader averages the map
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
`/uif debug <player> point <x> <y>` then `click`, in canvas pixels (2048
wide for a default view). `/ui place <page> <cols> <rows> <name> <x> <y> <z>
<facing> [world]` places a wall without anybody aiming.
