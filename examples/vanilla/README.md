# Vanilla pages

Two UI Engine pages that look like the game's own screens and do more than they can.

- **`servers.html`**: the Multiplayer screen as a network's server selector. Click a
  server to select it, hover it to see who is on, Join to go. The Lobby row is live
  (`{online}`, `{player}`, refilled every 5 seconds).
- **`quests.html`**: a quest log in the game's book. Pick a quest on the left page to
  read it on the right, turn the page with the arrows, hover a reward for its tooltip,
  claim what is done. "Experienced" shows the reader's real level.

Neither has any script. A selection is a radio button behind each row (a `<label>` clicks
it), and `:checked ~` shows what goes with it. `vanilla.css` is the game's look in CSS:
its buttons, slots, panels and tooltips, measured off its textures, in its own font.

## The game's textures

The pages use the game's item, block and GUI textures, which are Mojang's and are not in
this repository. Copy them out of your own client jar into the pages folder:

```sh
java VanillaTextures.java ~/.minecraft/versions/26.3/26.3.jar pages
```

That writes `pages/vanilla/`: the textures, the title screen's panorama (which the launcher
keeps beside the jar), and the game's font as `minecraft.ttf` and `minecraft_bold.ttf`, built
from its glyph sheets pixel for pixel. Without them the pages fall back to Monocraft, which UI
Engine bundles: close to the game's font, but monospaced. Then copy `pages/` into `plugins/UIEngine/pages/` and open a page
with `/ui open servers` or `/ui open quests`.
