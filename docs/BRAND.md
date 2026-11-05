# Argus brand tokens (0.52.1)

Source: the owner's board `work/ref/argus/argus-brand-reference.jpg` and the logo `work/ref/argus/argus-logo-source.jpg`.
Code: `ui/ArgusBrand.kt` (tokens), `ui/ArgusMark.kt` (the A), `BrandName.kt` (the name).

## Palette
| Token | Hex | Use |
|---|---|---|
| Obsidian | `#000000` | dark ground (`GlassTokens.bg`), splash background, ink in light mode |
| Argus Blue | `#0056FF` | light-mode accent (7.6:1 on white), start of primary fills, launcher gradient |
| Electric Blue | `#2868FF` | end of primary fills (white text 4.68:1), focus glow, the arcs |
| Electric Blue, text tint | `#6A8FFF` | accent TEXT / icons on the dark ground (6.6:1 on black; plain Electric is only 4.23:1) |
| Steel Gray | `#9AA0AB` | secondary text on dark (8.6:1), Material `secondary`; on white decoration only (2.3:1) |
| Pure White | `#FFFFFF` | ink on dark, light ground |
| Cobalt night | `#06103D` | top of the dark ground gradient (cobalt → black) |
| Paper | `#F6F7F9` | light ground (white and silver) |

- Dark surfaces: vertical gradient `#06103D → #000000`, a faint cobalt glow, and two thin electric/cobalt light arcs
  (`drawArgusArcs`, `Modifier.glassBackground`). Primary buttons: linear `#0D28FF → #3466FF` with white text.
- Light mode: paper `#F6F7F9`, ink `#0A0A0A`, cobalt accent, silver hairlines.
- Every text pairing above meets WCAG AA (4.5:1) for normal text.

## Logo
- The A: four slanted brushed-silver bars, redrawn as vectors from the source (`work/ref/argus/mk.py` generates
  `res/drawable/ic_launcher_{foreground,background,monochrome}.xml`, `ic_stat_blaze.xml`; `render.py` the PNGs in every
  `mipmap-*dpi`). Silver gradient `#F7F9FB → #B9C1CA → #EDF0F3 → #8E98A3` along the top-left → bottom-right diagonal.
- Launcher: adaptive icon (obsidian → cobalt radial background with faint constellation dots, silver A in the 66 dp safe
  zone, white monochrome layer for themed icons), `ic_launcher_round`, Android 12+ splash on Argus Black (`values-v31`).
- Clear space: about one cap height of the A on every side (`ArgusBrand.CLEAR_SPACE` = 0.7 × the mark's height).

## Wordmark
- "ARGUS", uppercase, Inter Medium, letter-spacing 0.35 em (`ArgusBrand.wordmark()`), under or beside the A.
- Used on: onboarding ("Welcome to ARGUS", first page), Settings › About, the top pill outside a project.

## Type
- Aeonik (the board's face) is a paid licence, so the app bundles **Inter** (SIL Open Font License): Light 300, Regular 400,
  Medium 500, SemiBold 600, Bold 700 (`res/font/inter_*.ttf`, Latin/Greek/Cyrillic subsets) plus Inter Tight for display.
  Wired into `Type.Sans` / `Type.Display` and Material's `Typography` (`Type.material()`).

## Tiers
- Insight (fast) / Oracle (balanced) / Panoptes (deepest): labels and icons (eye / sparkle / insights) only; ids and the
  `blaze_family` values stay `spark` / `flare` / `inferno`.
