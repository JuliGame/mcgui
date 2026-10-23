# Vanilla pages

UI Engine pages in the game's own look, as tools and guides it does not have. Open one with
`/ui open <name>`.

- **`quests`**: a quest log in the game's book. Pick a quest on the left page to read it on
  the right, turn the page with the arrows, hover a reward for its tooltip, claim what is
  done. "Experienced" shows the reader's real level.
- **`ores`**: where every ore generates by height, with the reader's own Y marked live and
  the best ore to dig for there.
- **`bestiary`**: a journal of mobs: health in hearts, drops with chances, where they spawn.
- **`skills`**: six skills with XP bars, and a perk track for the one you pick.
- **`auction`**: listings by category with rarity colours, prices in emeralds, bids and a
  balance.
- **`events`**: a month of server events, with the day's details and prizes.
- **`crops`**: every crop's growth stages, light, yield and a farm layout.
- **`stable`**: pets and mounts with health, speed and jump, equipment, summon and rename.
- **`dungeon`**: dungeon cards with difficulty, bosses and loot, and a party to fill.
- **`discs`**: the music discs found, the ones still out there, and a record player.
- **`status`**: TPS as redstone lamps, memory and CPU bars, players online, a restart log.
- **`claims`**: a chunk map of who owns what, trusted players, and switches for PvP, mobs
  and fire.
- **`character`**: vitals, equipment, attributes and points to spend.
- **`crates`**: three crates, what each can drop and how likely, and the keys held.
- **`redstone`**: circuits drawn in block textures, step by step.
- **`mailbox`**: an inbox of letters, the open one on book paper, gifts to collect.
- **`cookbook`**: every food with hunger, saturation and effects.
- **`brewing`**: the brewing stand as a potion guide: pick a potion, see its ingredients and
  effects.
- **`news`**: the game's toasts as a news feed.
- **`rules`**: the server rules told as the End Poem; hover a rule for its penalty.

None has any script. A selection is a radio button behind each row (a `<label>` clicks it),
and `:checked ~` shows what goes with it; a tooltip is `:hover`. `vanilla.css` is the game's
look in CSS: its buttons, slots, panels and tooltips, measured off its textures, in its own
font.

## The game's textures

The pages use the game's item, block and GUI textures, which are Mojang's and are not in
this repository. Copy them out of your own client jar into the pages folder:

```sh
java VanillaTextures.java ~/.minecraft/versions/26.3/26.3.jar pages
```

That writes `pages/vanilla/`: the textures, the title screen's panorama (which the launcher
keeps beside the jar), and the game's font as `minecraft.ttf` and `minecraft_bold.ttf`, built
from its glyph sheets pixel for pixel. Without them the pages fall back to Mac's Minecraft, which
UI Engine bundles: an open recreation of the game's font. Then copy `pages/` into
`plugins/UIEngine/pages/`.
