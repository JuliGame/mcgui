# MC GUI: copyright, licence and extra terms

MC GUI: HTML and CSS pages in Minecraft, for vanilla clients.
Copyright (C) 2026 Josh (@joshdevous) and Stephen (@stephendotgg), resourcepack.ai

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU Affero General Public License as published by the Free
Software Foundation, either version 3 of the License, or (at your option) any
later version, together with the additional terms and additional permissions
below, which section 7 of that licence provides for.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.

The licence itself is in [`LICENSE`](LICENSE).

## What the licence asks, in short

This part is a summary to read first. The licence and the numbered terms below
are what count.

- **Running MC GUI as released asks nothing of you.** Install it, write pages
  for it, build plugins on it.
- **If you give anyone a copy**, changed or not, they get the source with it,
  under this same licence.
- **If you change MC GUI and run your version on a server**, section 13 has you
  offer its source to the people who use it over the network. We read a player
  who joins a server running MC GUI as one of those people: they point at,
  click and scroll its pages through their connection. So a server that runs
  its own version of MC GUI publishes that version's source, even if it never
  hands the jar to anyone.

## Additional terms (section 7)

**1. Keep the credits.** (Section 7(b).) Every copy and every modified version
keeps these as they are: the copyright notices, this file, and the credit to
MC GUI. A modified version, whatever it is called, says

> Based on MC GUI by resourcepack.ai (https://mcgui.dev)

in two places: near the top of its README or main documentation, and in the
heading its main command prints, where MC GUI prints its own name and version.
That heading is one of the work's Appropriate Legal Notices.

**2. Do not pass it off.** (Section 7(c).) You must not misrepresent where
MC GUI, or any part of it, came from. You must not claim that you wrote it, or
present a version based on it as an original work of yours. A modified version
is marked as modified, carries a name of its own, and is not presented as
MC GUI itself or as made, approved or supported by resourcepack.ai.

**3. The names stay ours.** (Section 7(e).) This licence gives no right to the
names MC GUI, mcgui.dev, ResourcePack AI and resourcepack.ai, or to their
logos, beyond what it takes to give the credit in term 1 and to say truthfully
that your work uses or is based on MC GUI.

## Additional permissions (section 7)

**4. Plugins that use MC GUI.** You may make a separate plugin that uses MC GUI
and release it under any terms you choose, closed source and paid included, as
long as all of this holds:

- **It contains none of MC GUI.** No source, classes, shaders or pack files
  copied from this repository, changed or unchanged. It compiles against
  MC GUI (`compileOnly`, never shaded) and runs beside an MC GUI jar that is
  installed on the server separately. The short code samples in the README's
  "For plugin developers" section are free to copy.
- **It reaches MC GUI only from outside:** by calling the public classes and
  methods of the installed plugin, listening for its events, opening its pages
  and running its commands.
- **It says that it uses MC GUI.** Wherever the plugin is described (its
  documentation, and every page it is sold or downloaded from) names MC GUI
  and links to https://mcgui.dev or to this repository.
- **It does not claim MC GUI as its own.** You do not present MC GUI, its
  renderer, its framework or what they draw as work of yours, and you do not
  hide or remove MC GUI's own notices.

This permission covers your plugin only. MC GUI stays under the licence and the
terms above, and so does any version of MC GUI you change.

**5. Your pages are yours.** The HTML, CSS, scripts, pictures, cursors and
sounds you write for MC GUI to show are not covered by this licence, and
neither are the pictures MC GUI draws from them.

**6. The resource pack.** The resource pack MC GUI builds may be sent to
players and merged into another resource pack. Merging it does not bring the
rest of that pack under this licence. MC GUI's own files inside it stay under
this licence.

## Other people's work in this repository

The bundled fonts keep their own licence, the SIL Open Font License
(`src/main/resources/fonts/*-OFL.txt`). The libraries the plugin jar carries
keep theirs.
