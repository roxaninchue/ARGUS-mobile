# 04 · Design system + information architecture

> Slice 4 of the ARGUS overhaul (see `overhaul.md`). Owner: Specialist 4 (product design / IA).
> Target: the one ARGUS Android app (`argus-mobile`, Kotlin + Compose) that holds every feature of Argus-mobile (AM), A-team (AT) and Bot HQ (BH).
> **Base look: A-team** (v2.16+ "chat-first + liquid glass", `a-team/app/www/app.css`). Sources read for this doc are listed in §0.
> Status: **partial** — §0–2 published, §3–7 in progress. Decisions marked **[D]**, open questions marked **[Q]**.

Contents
0. Sources and what each brings
1. Design principles
2. Design system (tokens, components, A-team CSS → Compose map)
3. Information architecture (structure, life-domain team, entry points, feature → location table)
4. Key screen specs (ASCII wireframes)
5. Accessibility and one-hand rules
6. Creative ideas that make it feel like one product
7. Build notes, hand-offs to other slices, open questions

---

## 0. Sources and what each brings

| Repo | Files studied | What we keep for the design |
|---|---|---|
| A-team | `app/www/index.html`, `app.css` (73 KB, every section), `app.js` (home, bot chat, sheets, top bar), `chat.js` (tool cards, grouped steps, attach sheet), `cloud.js`/`browser.js` (computer view), `manager.js`, `notify.js` (pop-up + edge glow), `notch.js`, `review.js`, `bgjobs.js`, `memory.js`, `icons.js` (Lucide 0.460), README/HANDOFF | **The whole visual language**: monochrome ink-on-ground, frosted chrome, edge-lit glass cards, orb avatars, chats-as-bots home, "N need you" banner, pins, the pill composer, chips row, sheets with grab handle, menu rows (44 dp icon disc + title + sub), notch switcher, pop-up + edge glow notifications, tool cards/grouped steps, computer view card. |
| Argus-mobile | `docs/BRAND.md`, `docs/glass-design-system.md`, `HANDOFF-argus-hark-design.md`, `app/blaze-docs/about.md` (feature + settings map), `ui/` (GlassKit, StreamOrb, RichPreview, `ui/space/*`), `agent/SlashCommands.kt`, `ViewRouter.kt`, manifest (tile, widget, assist, share, wake word) | **The Compose kit to re-skin** (`Modifier.glass`, `GlassCard`, `GlassSheet`, `GlassFloatingWindow`, `MotionTokens` 150/220/320), Inter + Inter Tight already bundled, ARGUS brand (A mark, Cobalt #0D28FF / Electric #3466FF), the water-droplet stream orb, rich reply blocks, Spotlight, system entry points (Hey Argus, ASSIST, QS tile, widget, share, floating orb, Magic Hand), and ~40 feature areas. Note: `ui/space/` (Hark-style Home | Chat | Projects shell) is on `main`; this doc **replaces its top bar/tabs with the A-team shell** and keeps its backdrop as an optional ground. |
| Bot HQ | `web/src/styles/tokens.css`, `chat.css`, components (`Sidebar`, `Transcript`, `ActivityLine`, `cards/Cards.tsx`, `DetailsPanel`, `ComputerViewer`, `BotSettingsModal`, `AppSettings`, `VoiceCallBar`, `CreateGroupModal`), server routes + MCP tools, screenshots `chat-dark.png`, `viewer.png`, `home-screen.jpg` | **Interaction patterns**: live activity line ("Looking at your calendar · 5 steps") folding into "Worked for 2m 10s · 7 steps", step list with coloured status dots (done / failed / *Waiting for your OK*), approval / question / connect / draft cards, details panel with live screen + Activity · Tasks · Library tabs, Engine (provider/model/subscription sign-in), Voice chat per bot (voice, speed, language), full-size viewer with Live pill + take control, bot's own dressed desktop (clock, "Welcome back, Nova", apps), groups (bots in one room), Permissions (always allow / ask / never per tool), check-in times, event routines (Composio triggers). Its Grok artwork is **not MIT** → we do **not** ship BH avatars; A-team orbs replace them. |

"Muse-style social connections" and "Dots-style agent computer" are named in the brief; BH has the computer, but no social graph in code, so §3/§4 specify the **People** graph fresh (person nodes, the bots that serve each relationship, and bot↔bot links), in the A-team idiom.

---

## 1. Design principles

1. **Chat is the surface, the team is the structure.** Every specialist is a chat (AT). Everything a bot does — jobs, approvals, screens, files, reviews — appears *in its thread first* and is *collected* elsewhere (Inbox, Desk, Library). Nothing lives only in a settings page.
2. **Monochrome until it matters.** Ink on ground, glass for depth. Colour is reserved for: a bot's identity (its orb), state (red overdue/failed, green done/live, amber waiting-for-you, blue unread), and the ARGUS brand (the Manager's cobalt "A" sphere, focus glow). Never decorative colour.
3. **One composer, everywhere.** The bottom pill is the universal entry: plain language, `@bot`, `/command`, `$ shell`, `+` attach/create, hold-to-talk. Same position on every mode.
4. **Show the work, fold the work.** Live: one activity line with a timer. Done: one chip "Worked for 2m 10s · 7 steps" that opens. Never a wall of tool logs by default.
5. **Ask before acting, in one tap.** Writes that send, pay, post, book or delete land as approval cards (thread + Inbox + notification action). Approve/Deny are always thumb-reachable.
6. **Nothing jumps.** AT's polish rule: no layout shift on keyboard, progress ticks or streaming; tabular numbers; fixed-height lines; soft renders.
7. **Reach before reveal.** Primary actions in the bottom 45 % of the screen; destructive actions never in the thumb's resting zone.

---

## 2. Design system

All tokens live in a new package `ui/argus/` (name **[D]**: `ArgusTheme`, `ArgusTokens`, `ArgusType`, `ArgusShapes`, `ArgusMotion`, `ArgusGlass`), replacing `GlassTokens`/`SpaceLook` gradually (see §7). Material3's `colorScheme` is mapped from the same tokens so stock components inherit the look.

### 2.1 Colour — core (from A-team v2 + liquid glass overrides)

The final cascade in `app.css` is what ships: base v2 tokens (line 208) overridden by the liquid-glass block (line 416) and the readability fix (line 476). Values below are those effective values.

| Token (Compose) | CSS var | Dark | Light | Use |
|---|---|---|---|---|
| `bg` | `--bg` | `#0B0B0D` | `#EEF0F4` | screen ground |
| `ink` | `--ink` | `#F2F2F4` | `#111114` | primary text, primary fills (`.btn.p`, user bubble, send) |
| `onInk` | (`--bg` on ink) | `#0B0B0D` | `#FFFFFF` | text/icons on ink fills |
| `mute` | `--mute` | `#9B9BA3` | `#7C7C84` | secondary text, previews |
| `dim` | `--dim` | `#6B6B73` | `#A2A2AA` | timestamps, placeholders, meta |
| `soft` | `--soft` | `white 7 %` | `black 4.5 %` | pressed rows, fields, chips (flat) |
| `soft2` | `--soft2` | `#24242A` | `#E8E8EC` | back buttons on full pages |
| `line` | `--line` | `white 9 %` | `black 8 %` | hairlines, card borders (flat) |
| `line2` | `--line2` | `white 22 %` | `black 22 %` | strong hairline, checkbox, sheet handle |
| `glassFill` | `--gl` | `white 5.5 %` | `white 62 %` | glass surface fill |
| `glassFill2` | `--gl2` | `white 9 %` | `white 78 %` | raised glass (agent bubble, buttons, icon buttons) |
| `glassRim` | `--glb` | `white 14 %` | `white 90 %` | glass 1 dp border |
| `glassRim2` | `--glb2` | `white 26 %` | `black 12 %` | focused field border, progress track |
| `specular` | `--spec` | `linear(180°: white 14 % → 2 % @42 % → 0 @60 % → 4 %)` | `linear(white 95 % → 40 % @45 % → 20 %)` | top sheen on glass |
| `chrome` | `--chrome` | `rgba(14,14,18,.58)` | `rgba(255,255,255,.60)` | frosted top bar, composer, chips, toast |
| `sheet` | `--sheet` | `rgba(22,22,27,.93)` | `rgba(250,250,252,.94)` | sheets, popovers, notification pop-up |
| `scrim` | `.scrim` | `black 35 %` | `black 35 %` | under sheets |
| `danger` | `--od` | `#FF5A5F` | `#E5484D` | overdue, failed, delete, red dot |
| `success` | `--okc` | `#3ECF8E` | `#16A36A` | working/live dot, done ticks |
| `unread` | `--blue` | `#3B82F6` | `#2F7BFF` | unread dot only |
| `waiting` | `--warn` (`.mgr-risk`) | `#E0A020` | `#B97F0A` (darkened for AA) | "Waiting for your OK", risk |
| `manager` | `.mbadge` | `#D4A72C` @22 % bg / text | same | Manager badge, Manager row tint 7 % |
| `brandCobalt` | AM BRAND | `#3466FF` (glow), text tint `#6A8FFF` | `#0D28FF` | **ARGUS-only**: Manager orb glow, focus ring, wordmark, onboarding primary gradient |

Ground decoration ("the light behind the glass", `.bg`): three soft radial blobs — dark: white 10 % @ (15 %, 8 %), white 6 % @ (92 %, 30 %), white 7 % @ (40 %, 100 %); light: `#FFFFFF`, `#DFE3EC`, `#E6E9F0` at the same spots. Compose: `Modifier.argusGround()` draws three `Brush.radialGradient` on a `drawBehind`. **[D]** Optional grounds in Appearance: *Plain* (default, the blobs), *Orbit* (AM's `argus_space.webp` Earth view under a 60 % scrim — keeps the Hark-design work alive), *Cobalt night* (AM BRAND `#06103D → #0A0A0A` + `drawArgusArcs`). Cards must pass contrast on all three (reuse AM `ContrastTest`).

**Bot identity palette** (`COLORS` in app.js, the ARGUS Agents orb art) — 8 swatches + any custom hex (`.sw.any` conic picker):
`violet #8B5CF6 · green #22C55E · amber #F59E0B · red #EF4444 · cyan #06B6D4 · indigo #6366F1 · blue #3B82F6 · silver #CBD5E1`.
BH's 11 named colours (`black, brown, red, orange, yellow, green, cyan, blue, violet, magenta, gray`) map onto these on import (black→silver orb with ink ring, brown→amber-mix, magenta→`#F93498` custom). A bot's colour tints *only*: its orb, its edge-glow/pop-up border (`--gc`), its live dot in the roster, its domain chip, and the send button inside its thread at 100 % (BH "bot-action"). User bubbles stay ink (AT), never bot-coloured — **[D]** AT wins over BH's pink bubbles: calmer, and colour stays meaningful.

**Life-domain hues** (new, derived from the bot palette so domains read as teams): Health `#22C55E`, Money `#F59E0B`, Work `#6366F1`, Home `#06B6D4`, People `#EC4899` (custom), Learning `#8B5CF6`, Travel `#3B82F6`, Create `#EF4444`, Mind `#CBD5E1`, Phone `#9B9BA3`. Used for the domain lead's default orb and the 3 dp domain tick on rows — never as large fills.

### 2.2 Type

Font: **Inter** (bundled in AM, OFL; AT asks for `Inter, system-ui`). Display: **Inter Tight** (bundled). Serif accent: AT's `talk-hello` uses Georgia 30/400 and AM-Hark used serif headlines → **[D]** bundle **Instrument Serif** (OFL) as `ArgusType.Serif` for exactly three places: the Chat-mode greeting, the Home "day line" and empty-state hero lines. Mono: **JetBrains Mono** (OFL) or Roboto Mono for code, tool commands, terminal.

| Style (Compose `ArgusType.*`) | Source (CSS) | Size / line / weight / tracking | Use |
|---|---|---|---|
| `display` | `h1.big` | 32 / 38 / 750 (Inter Tight Bold) / −0.8 | Home team name, page titles |
| `serifHello` | `.talk-hello b` | 30 / 36 / 400 serif / −0.3 | Chat greeting, empty heroes |
| `title1` | `.setup h1` | 27 / 33 / 750 / −0.6 | onboarding, full pages |
| `title2` | `.intro b`, `.hello b`, `.sheet h3` | 20 / 26 / 650 (sheet 19) | thread intro name, sheet titles |
| `topTitle` | `.who b`, `.cnp-title` | 17 / 22 / 650 | top bar name |
| `rowTitle` | `.crow b` | 16 / 21 / 620 | chat rows |
| `body` | `.msg` | 15 / 21 (1.38) / 400 | messages, fields (fields 16 to stop zoom — keep 16 for inputs) |
| `bodyStrong` | `.task .tt`, `.menu b` | 15.5 / 21 / 600 | menu row titles |
| `sub` | `.sub`, `.need` | 14.5 / 20 / 400 | subtitles, banners |
| `preview` | `.crow p` | 14 / 19 / 400 mute | chat row preview |
| `tool` | `.tc-h`, `.tg-h` | 13.5 / 18 / 560 | tool + step lines |
| `meta` | `.who small`, `.due`, `.crow b em` | 12.5 / 16 / 400 dim | times, status lines |
| `section` | `.sec`, `h2` | 12 / 16 / 650 / +1.0, UPPERCASE mute | section heads |
| `chip` | `.chipb`, `.chip` | 13.5 (action chips) / 11.5 (status chips) / 500 | chips |
| `pill` | `.pill`, `.mbadge` | 11 / 14 / 600 | badges |
| `mono` | `.tc-cmd`, `.cb pre` | 12.5 / 18 / 400 | code, commands |

Rules: all ticking numbers use `fontFeatureSettings = "tnum"` (AT polish block). Respect font scale up to 200 %: rows grow, never clip (no fixed heights on text rows except the 1-line progress line which uses `minHeight`).

### 2.3 Spacing (4 dp grid)

| Token | dp | From |
|---|---|---|
| `space.xxs` | 2 | `.pins` margins, tick offsets |
| `space.xs` | 4 | chip gaps in dense rows, `.crow` padding-x |
| `space.s` | 6 | menu gap, chip row gap |
| `space.m` | 8 | button gaps, thread gap 9 → 8 |
| `space.ml` | 10 | card inner gaps, top bar gap |
| `space.l` | 12 | composer side inset, row icon gap |
| `space.xl` | 14 | card padding, banner padding, top bar side padding |
| `space.page` | 16 | page horizontal padding (`main`) |
| `space.section` | 22 | gap above a section head |
| top bar height | 62 + status inset | `.top` |
| composer height | 58 | `.cmd` |
| content bottom pad | 100 (150 in a thread) + nav inset | `main` |
| chips row offset | composer + 10 | `.chips` bottom 82 |

### 2.4 Shapes

| Token | Radius | From |
|---|---|---|
| `shape.composer` | 29 dp (full pill) | `.cmd` |
| `shape.sheet` | 26 dp top corners | `.sheet` |
| `shape.card` | 20 dp | `.card`, `.jcard` |
| `shape.bubbleUser` | 20/20/6/20 (tail bottom-end) | `.msg.u` |
| `shape.bubbleAgent` | 20/20/20/6 — but AI replies sit on the page **without** bubble (v2.16) | `.msg.a`, v2.16 rule |
| `shape.banner` | 18 dp | `.need`, `.chipb` |
| `shape.row` | 16 dp (pressed state) | `.crow`, `.alljobs` |
| `shape.tool` | 14 dp | `.tc`, `.tg`, `.cv`, `.attc` |
| `shape.button` | 13 dp (sm 11) | `.btn` |
| `shape.field` | 12 dp | `.field input`, code blocks |
| `shape.checkbox` | 7 dp (21 dp box) | `.ck` |
| `shape.circle` | 50 % | avatars, `.ib`, `.me`, FAB, send |

### 2.5 Elevation and glass

Four levels (AT: "blur only on fixed layers, for smooth scrolling"):

| Level | CSS | Compose |
|---|---|---|
| **Flat** | `--bg`, hairline `--line` | `Modifier.background(bg)`; lists, rows, threads |
| **Surface** (no blur) | `.glass,.card,.jcard`: `background: var(--spec), var(--gl)`; `border 1px var(--glb)`; `box-shadow: var(--ghl), 0 6px 22px rgba(0,0,0,.12)` (dark .35/26 px) | `Modifier.argusGlass(Surface)` = fill `glassFill` + vertical `specular` brush + 1 dp `glassRim` border + inner top highlight (1 dp line white 16 %) + `shadow(6.dp, ambient 12 %)`. Pass `shadow=false` when nested (AM rule). |
| **Chrome** (frosted) | `.top, .cmd, .chipb, .toast`: `background var(--chrome)`; `backdrop-filter: blur(22–24px) saturate(160–170%)` | **Haze** (`dev.chrisbanes.haze`, Apache-2.0) `hazeSource` on the content pager + `hazeEffect(style = ArgusHaze.chrome)` (blur 24 dp, tint `chrome`, noise 0) on top bar, composer, chips. API < 31 or low-RAM (`ActivityManager.isLowRamDevice`) → the tint alone at 0.92 alpha (AM `glassBlur` already gates this). |
| **Sheet** | `.sheet`: `var(--sheet)` .93, blur 30, top border `--glb`, shadow `0 -10px 40px rgba(0,0,0,.35)` | `ModalBottomSheet` restyled: container `sheet`, `hazeEffect(blur 30)`, top rim, drag handle 40×5 `line2` radius 9 (`.sheet::before`) |

Shadows: `gsh` = `0 12px 40px black 45 %, 0 2px 8px black 30 %` (dark), `0 10px 34px rgba(30,30,60,.10), 0 2px 6px .06` (light) → `ArgusElevation.float` for composer, FAB, pop-up. Primary fills (`.btn.p`, send, `.need`) get a top gloss `linear(white 20 % → 0 @60 %)` over `ink` + `0 6px 18px black 22 %`.

### 2.6 Motion

| Token | CSS source | Compose |
|---|---|---|
| `motion.press` | `.pressing{transform:scale(.97)}` `.15s` | `Modifier.pressScale(0.97f)`, `tween(150)` |
| `motion.sheetIn` | `@keyframes up` 40 px + fade, `.2s ease-out` | `slideInVertically { 40.dp } + fadeIn`, `tween(200, FastOutSlowIn)` |
| `motion.pageIn` | `.cnp animation up .18s` | same, 180 ms |
| `motion.notch` | `cubic-bezier(.2,.9,.3,1.2)` .25 s / pill .28 s `(.2,.9,.3,1.15)` | `spring(dampingRatio = 0.62f, stiffness = 520f)` (slight overshoot) |
| `motion.popIn` | `.popn` `.35s cubic(.2,.9,.3,1.12)` from −140 % | `spring(0.7f, 400f)` from `-fullHeight*1.4` |
| `motion.pulse` | `@keyframes pulse 50%{opacity:.35}` 1 s | `infiniteRepeatable(tween(500), Reverse)` on alpha 1→.35 (typing dots stagger 200 ms) |
| `motion.spin` | `.spin` 1 s linear | `rotate` infinite 1000 ms linear (live step loader) |
| `motion.edgeGlow` | `edgepulse 2.6s` (8 key frames) | `keyframes { durationMillis = 2600 … }` on the edge glow alpha |
| `motion.chevron` | `.tc-c` rotate 180 .15s | `animateFloatAsState(tween(150))` |
| AM `MotionTokens` | 150 / 220 / 320 ms, card spring ~0.8 | keep; `short=150, medium=220, long=320` |
| stream reveal | AM: last ~28 chars fade in, droplet rides text end | keep `StreamOrb` but **monochrome** (ink droplet; cobalt only for the Manager) |

Reduced motion (`Settings.Global.ANIMATOR_DURATION_SCALE == 0` or the in-app switch): every animation snaps/fades ≤ 100 ms, edge glow becomes a single 400 ms fade, pulses stop (AT `prefers-reduced-motion` rule).

### 2.7 Icons and avatars

- **Icons: Lucide v0.460** (ISC, AT `icons.js`), stroke 2 (1.7 in tab-like contexts, 2.2 in composer), round caps/joins, `currentColor`. Compose: generate `ImageVector`s from the same SVG paths into `ui/argus/icons/Lucide.kt` (script reads AT `icons.js`; ~90 icons used) — no font, no Material icons in new UI. Sizes: 15 (chips) · 16 (tool cards, sm buttons) · 18 (buttons) · 20 (icon buttons) · 21 (composer) · 22 (menu discs) · 24 (flat back, full-page back).
- Key icon semantics (keep consistent everywhere): `bot` Bots · `message-circle` Chat · `bell` Inbox · `plus` New · `search` · `brain` Memory · `scroll-text` Soul · `cpu` Engine · `blocks` Apps · `monitor` Computer · `repeat` Routines · `list-checks` Jobs/Tasks · `library` Library · `shield-check` Approvals/Permissions · `users` People · `workflow` Compose graph · `square-terminal` Terminal · `globe` Browser · `cloud` Cloud computer · `mic` Voice · `audio-lines` live call · `hand` Magic Hand / Take over · `flag` Needs you · `sparkles` Skills · `heart-pulse` Health · `wallet` Money · `briefcase` Work · `house` Home · `graduation-cap` Learning · `plane` Travel · `palette` Create · `notebook-pen` Mind · `smartphone` Phone.
- **Bot avatar = A-team orb** (`botSVG(face, colour)`: glowing glass sphere, six ring styles *Orbit, Tilt, Steep, Pure, Twin, Polar*, any colour). Compose: `BotOrb(face: Int, color: Color, size: Dp, state: OrbState)` drawn on `Canvas` (radial body hi/c/lo, halo, ring back/front arcs with tilt, specular ellipse). Sizes `xs 18 · sm 34 · md 50 (rows; AT 46→50) · lg 72 · xl 84 · hero 120`. `OrbState`: `Idle`, `Working` (ring rotates slowly 8 s/turn + success dot), `Waiting` (amber dot), `Talking` (body scales with voice amplitude), `Away` (desaturated 40 %).
- **Manager = the ARGUS "A" sphere** (`img/manager.png` in AT; in Compose draw AM's `ArgusMark` inside a cobalt orb with `drop-shadow 0 0 6px rgba(59,130,246,.45)`). Only the Manager uses cobalt.
- **Domain glyph badge**: a 16 dp disc at the orb's bottom-right with the domain's Lucide glyph (like BH's star badge on Barbie). Status dot (8 dp) at bottom-left (BH Sidebar).
- **People** (humans) use a *different* avatar language so you never confuse a person with a bot: circular photo/initials with a hairline ring, no glow.

### 2.8 Components

Every component lists: purpose · AT source · Compose API · key specs. All live in `ui/argus/kit/`.

**Chrome**
1. **`ArgusTopBar`** — AT `renderTop()`. Three variants:
   - *Home*: `[me ○]` (38 dp ink disc with initial → You/Settings; long-press → theme toggle, as AM Space avatar) · spacer · `[search ○] [bell ○•] [+ ○]` (38 dp `.ib` glass discs).
   - *Thread*: `[‹ flat]` `[orb sm]` `Name / ● Working · Role` (status line `.who small`: green Working, red "2 overdue", dim Idle, amber "Waiting for you") · `[⋯ ○]` → Bot profile.
   - *Page*: `[‹]` Title · optional actions.
   Frosted Chrome level; bottom hairline only in thread/list pages.
2. **`NotchSwitcher`** — AT `notch.js`: wordless 46×22 handle at top centre; pull or tap → pill drops with modes **Team · Chat · Life · Desk** (AT had Bots · Chat). Selected segment = ink pill, onInk text. Swipe up / tap outside / Back closes. Also mirrored by horizontal swipe between modes (§3.2).
3. **`Composer`** — AT `.cmd`: 58 dp full pill, 12 dp side insets, Chrome glass + spec sheen at 70 %. Leading `[+]` 42 dp disc (glass2) · text field 16 sp ("Tell the team…" on Team, "Message Nova" in a thread, "Ask anything" in Chat) · trailing **mic** (when empty) morphing to **send ↑** (ink disc) when text exists, to **stop ■** while streaming (`.cmd.streaming`). Hold mic = push-to-talk; tap mic = Voice mode. Grows to 5 lines max, then scrolls (never pushes the thread).
4. **`ChipsRow`** — AT `.chips`: horizontal chips 10 dp above composer (`Add job · Memory · Edit bot · Copy email` in AT; extended in §4.3). Hidden while attachments are pending (`.attbar` takes the slot) or a sheet is open.
5. **`MentionPopup`** — AT `.mention`: sheet-coloured card above composer listing bots (orb xs + name + role), commands (`/`), or files (`#`), 44 dp rows.
6. **`AttachmentBar`** — AT `.attbar/.attc`: 52 dp tiles (thumb 40 + name + ×).

**Lists**
7. **`BotChatRow`** — AT `crow()`: orb md · name (16/620) + time (12.5 dim) on one baseline · preview (14 mute; red bold "2 overdue · Send deck"; green "All clear") · unread dot 10 dp blue. Manager row: gold tint 7 % + "Lead" badge. Pressed: `glassFill2` + rim. Long-press → bot quick sheet (§3.5). Swipe-end → Pin; swipe-start → Mute/Archive.
8. **`PinStrip`** — AT `.pins`: 3–4 orbs lg with names, red 13 dp dot when overdue. Long-press to reorder.
9. **`NeedYouBanner`** — AT `.need`: full-width ink banner (glossy), flag icon, "**3 need you** · Nova, Atlas" + "Review ›". Opens Inbox filtered. Amber variant when only waiting approvals; hidden when zero.
10. **`TaskRow`** — AT `taskRow()`: 21 dp check (7 dp radius) · title · line (orb xs + bot name · waiting · high) · due (red when overdue). Priority tick 4 dp left on Desk.
11. **`MenuRow`** — AT `.menu button`: 44 dp glass disc icon · title 15.5/600 · sub 13 mute. Used in + menu, sheets, Settings.
12. **`SettingsGroup`** — AM Space profile: grouped Surface card of rows (icon + label + value/chevron, hairlines). Keeps the Hark-style settings pattern but in AT glass.
13. **`Segmented` / `FilterChips`** — AT `.seg`: 99 dp pills, on = ink; counts `em` at 60 %.

**Thread**
14. **`ThreadIntro`** — AT `.intro`: orb xl, name, role · email, engine pill (`● gateway / model`), portfolio text. Sits atop every bot thread.
15. **`UserBubble`** — ink fill, onInk text, `shape.bubbleUser`, max 82 %, gloss shadow. Attachments above text.
16. **`AgentText`** — v2.16 rule: no bubble, full width, markdown (`.mdb`: lists, task boxes, code blocks with copy, tables → AM table card, quotes). Streams with AM droplet.
17. **`SystemNote`** — AT `.msg.s`: centred, dashed `line2` border 16 dp, 13 mute ("**2 changes** ✓ Job *Send deck* · Fri").
18. **`ActivityLine`** (live) — BH `ActivityLine` + AT `tg-run`: inline row under the last message: spinner 16 · present-tense step ("Looking at your calendar") · `· 5 steps` (dim mono) · chevron. Under it, while open, the **step list**: dot (green done, red failed + "Failed" red, amber "Waiting for your OK", spinner current) · bold verb ("Reading the thread") · dim detail ("4 messages"). Plus the **working card** (BH): orb sm + "Barbie / Working in Google Calendar" + elapsed "Working for 1m 24s" — on phone this collapses into the activity line's right side as `1:24`.
19. **`WorkChip`** (done) — BH "Worked for 2m 10s · 7 steps ›": 32 dp pill, Surface glass, 13 sp; tap expands to AT grouped steps (`.tg` with sub-groups: *Edits, Reads, Commands, Cloud computer, Browser, Memory, Team, Jobs*). Summary when >1 kind: "7 steps: 3 reads, 2 commands, 1 browser, 1 approval". Failed turn: chip outlined red "Stopped after 12 steps · Go on".
20. **`ToolCard`** — AT `.tc`: 40 dp header (icon · title · status · chevron), body `$ cmd` mono + output (max 260 dp scroll), Undo button for reversible edits.
21. **`ApprovalCard`** — BH `ApprovalCard` + AT `mgr-card` + browser confirm. Surface glass card, amber 3 dp left edge: header `shield-check · Nova wants to` (12 mute), title "**Send email** to alex@… — *Re: seat cap*" (15/600), preview block (draft text / amount / target, 3 lines + "Show all"), meta line "Gmail · write action", buttons **Deny** (glass) · **Approve** (ink, right, thumb side) + overflow `Always allow for Gmail send` (BH policy → Permissions). States: pending → approved (✓ green, collapses to one line) → denied (struck, dim) → expired.
22. **`QuestionCard`** — BH `ask_user`: question + 2–4 option chips + "Reply…" field.
23. **`ConnectCard`** — BH `request_app_connection` / AT connectors: app logo 46 dp (AT `.cn-logo`), "Nova needs **Google Calendar**", **Connect** (opens browser PKCE / Composio), "Not now".
24. **`DraftCard`** — BH draft: editable draft (email/post/DM) with Copy, Edit, Send (→ approval).
25. **`LiveScreenCard`** — AT `.cv` + BH viewer thumb: header `● Live · Nova's computer · "Clicking at 640, 400…"`, 16:10 frame (black), buttons **Watch** (full viewer) · **Take over** (hand) · **Stop** (danger glass). Polls only while visible (AT rule).
26. **`JobCard`** — AT `.jcard`: "NOVA · JOBS — 3 open", job rows with checks, "+N more".
27. **`OkCard`** — AT `.okc` "Needs your ok": overdue job, **Tomorrow** · **Done**.
28. **`ReviewCard`** — AT `review.js`: collapsible; severity chips (blocker red, major amber, minor blue) and findings with file:line + fix.
29. **`FileCard`** — AT `.afile`: icon 36 · name · size/path · Open · Share · Save to Downloads; image/HTML preview.
30. **`HandoffCard`** (new, bots talking to bots) — "Nova → Atlas: *Find flights LUN–CPT for Jan 5*" with both orbs connected by a dotted line (AT `.edge.lead` style), status, "Open conversation" (the bot↔bot room). See §6.
31. **`RichBlocks`** (AM) — diagram, chart, table, compare, image(s), mini tools, forms, secure form → detached Surface preview cards, `GlassFloatingWindow` viewer. Restyle only.

**Overlays**
32. **`ArgusSheet`** — handle, title 19/650, content, sticky action row (Cancel glass / Primary ink) at the bottom.
33. **`Toast`** — sheet glass, 13 radius, sits above the composer (AT moved it bottom), moves to top while a sheet is open.
34. **`PopNotification` + `EdgeGlow`** — AT `notify.js`: in-app pop-up (orb 42 + name + time + 2-line text, border tinted with bot colour `--gc` 45 %), two crisp haptic taps, both screen edges pulse in the bot's colour (2.6 s). Swipe up dismisses, tap opens thread. Approval pop-ups get inline **Approve**.
35. **`CloudChip`** — AT `.cloud-chip`: centred under top bar "● Cloud on · $0.055/h" whenever a cloud computer runs. Tap → Computers.
36. **`EmptyState`** — AT `.empty`/`.hello`: big icon 30 at 55 %, title 17, body mute, one or two buttons; serif hero for first-run ("Build your team").
37. **`ProgressLine`** — AT `.wsline`: 44 dp one-line status with a 44×4 bar (setup, downloads, workspace).
38. **`OrbFab`** — AM floating orb (overlay over other apps) restyled as a 56 dp BotOrb of the current bot (Manager by default); also the in-app voice FAB in Life/Desk modes where the composer is collapsed.

### 2.9 A-team CSS → Compose mapping (implementation table)

| A-team selector / var | Compose | Notes |
|---|---|---|
| `:root/[data-theme]` vars | `ArgusColors` (`@Immutable data class`) provided by `LocalArgusColors`; `ArgusTheme(dark: Boolean, ground: Ground)` | System/Light/Dark + ground |
| `.bg` blobs | `Modifier.argusGround()` | 3 radial brushes |
| `.top` | `ArgusTopBar` in `Scaffold.topBar`, `hazeEffect` | height 62 + `WindowInsets.statusBars` |
| `.cmd` | `Composer` in `Scaffold.bottomBar` with `imePadding()` | fixed; never moves content except by inset |
| `.chips`, `.chipb` | `ChipsRow` (`LazyRow`, `contentPadding 12.dp`) + `ArgusChip` | |
| `.crow` | `BotChatRow` | `combinedClickable` (tap, long-press) |
| `.pins/.pinb` | `PinStrip` | |
| `.need` | `NeedYouBanner` | |
| `.task`, `.jrow`, `.ck` | `TaskRow`, `ArgusCheck` | 48 dp touch target around 21 dp box |
| `.msg.u/.msg.a/.msg.s` | `UserBubble/AgentText/SystemNote` | |
| `.tc/.tg` | `ToolCard/StepGroup` | |
| `.jcard/.okc` | `JobCard/OkCard` | |
| `.mgr-card/.mgr-confirm` | `ApprovalCard(kind = Manager)` | |
| `.cv`, `#cv-full` | `LiveScreenCard`, `ComputerViewer` (full screen, black) | |
| `.rv-*` | `ReviewCard` | |
| `.afile/.aimg/.pvbox` | `FileCard`, `FilePreview` | HTML preview via sandboxed WebView |
| `.sheet/.scrim` | `ArgusSheet` | `ModalBottomSheet` restyle |
| `.menu button/.mi` | `MenuRow` | |
| `.seg` | `FilterChips` | |
| `.btn/.btn.p/.btn.d/.btn.sm` | `ArgusButton(kind = Glass/Primary/Danger, size = M/S)` | Danger = red 16 % fill, red 50 % border, `#FF6B62` text (light `#D70015`) |
| `.ib/.ib.flat/.me` | `ArgusIconButton(kind = Glass/Flat/Avatar)` | 38 dp visual, 48 dp target |
| `.field/.search` | `ArgusField`, `ArgusSearch` | glass fill, focus ring 3 dp `gl2` + rim2 |
| `.toast` | `ArgusToastHost` | |
| `.popn/#edge-glow` | `PopNotification`, `EdgeGlowOverlay` | overlay window for outside-app glow |
| `.notch/.npill` | `NotchSwitcher` | |
| `.cloud-chip` | `CloudChip` | |
| `.mention` | `MentionPopup` | |
| `.av.bot` + `botSVG()` | `BotOrb` | Canvas |
| `.gate/.setup` | `OnboardingScaffold` | reuse AM SetupPlan |
| `.cnp/.cn-*` | `ConnectorsPage` | full-screen plugin list, floating search pill at bottom |
| `.term` + xterm | AM `BlazeTerminalActivity` restyled dark-mono | Terminal stays dark in both themes (AT rule) |
| `#graph .node/.edge` | `ComposeGraph` (Canvas, pan/zoom) | edges: solid owns, dashed waits-on, dotted lead/reports-to |
| `.mbadge` | `ArgusBadge(kind = Lead)` | gold |
| `--safe-b` | `WindowInsets.navigationBars` | |
| `font-variant-numeric:tabular-nums` | `TextStyle(fontFeatureSettings = "tnum")` | |
| `prefers-reduced-motion` | `LocalReducedMotion` | |

---

## 3. Information architecture

### 3.1 Mental model

> **"Your life, run by a team you can text."**

Three layers, always in this order of importance:

1. **You** — your profile, your people, what the team knows about you, your approvals. (Avatar, People, Inbox.)
2. **The team** — the **Manager** (ARGUS, the cobalt "A") plus **life-domain leads** (Health, Money, Work, Home, People, Learning, Travel, Create, Mind, Phone) plus any **specialists** you hire or make under them (Coder, Researcher, Designer, "Inbox", "Nova"…). Each is a chat with a soul, memory, engine, apps and (optionally) a computer.
3. **The work** — conversations, jobs/tasks, routines, approvals, files in the Library, projects, computers. Work is always *owned by a bot* and *visible in that bot's thread*, then *collected* in Desk.

The Manager is the front door (Chat mode and the default `Tell the team…` target): it routes, delegates (bot-to-bot), creates bots, and keeps the user profile. Domain leads are the second door: pinned, one tap. Specialists are the third: reachable by `@name`, the roster, or their lead.

### 3.2 Top-level structure

No bottom tab bar (A-team removed it to give the composer the bottom). Four **modes**, switched by the **Notch** (AT), by **horizontal swipe** on the content (pager, with the notch pill flashing the new name), or by `/team /chat /life /desk`.

```
                 ┌──────────── Notch pill (pull / tap top centre) ────────────┐
                 │   [ Team ]   Chat     Life     Desk                         │
                 └─────────────────────────────────────────────────────────────┘
   ◀ swipe ▶      TEAM              CHAT               LIFE               DESK
                 your bots as     plain chat with    domains, people,   inbox, jobs,
                 chats (home)     the Manager        today, projects    routines, library,
                                  (Argus's one                          computers
                                  conversation)
   always:  top bar (me · search · bell · +)          bottom: the Composer (+ · text · mic/send)
```

| Mode | What it answers | Contents (top → bottom) | Composer target |
|---|---|---|---|
| **Team** (default) | "Who's doing what, and what needs me?" | Team name (display) · sub line "9 bots + Manager · 4 jobs today · 1 overdue" · domain filter chips (All · Health · Money · …) · **Need you** banner · **Pins** (3–4 leads) · **Manager** row · chat rows by recency (bots and **groups**) · "Unassigned" row · "All jobs" button | "Tell the team…" → Manager routes (or on-device Needle parses a command) |
| **Chat** | "Just ask." | Serif greeting by time of day (AT talk-hello / AM Space greeting) → the continuous conversation with the Manager: AM's rich replies, follow-up chips, projects, private chat toggle (ghost in top bar) | "Ask anything" → Manager, full AM agent (phone control, browser, media, Linux…) |
| **Life** | "How is my life going?" | Day line (serif: "Wednesday · 3 things today") · **Today** strip (calendar + routines + check-ins as a horizontal timeline) · **Domains** grid (2-col tiles: lead orb, domain name, one live line, one panel value) · **People** card (top 5 people + "See graph") · **Projects** (AM) · **Panels** (AM Home cards: weather, latest answer, suggested asks) | "Ask about your life…" → Manager with Life context |
| **Desk** | "Where's the stuff?" | Segmented: **Inbox** (approvals, questions, connects, failures) · **Jobs** (AT jobs + BH tasks, filters Today/Upcoming/Overdue/Done/Unassigned) · **Routines** (schedule + event + check-ins) · **Library** (all files bots made) · **Projects** · **Computers** (screens, Ubuntu workspace, cloud, browser tabs) | "Add a job, routine or file…" → Needle command parser first |

Global, outside the modes:
- **Top bar** (all modes): `me` avatar → **You** (profile + Settings) · `search` → **Spotlight** (AM: bots, chats, messages, jobs, files, memory, settings, apps, actions, web) · `bell` → **Inbox** sheet (dot when anything waits) · `+` → **New** menu.
- **Thread** (any bot or group) opens over the mode as a full page; Back returns to the mode you came from (AT NAV stack).
- **Bot profile** opens from the thread's `⋯`, the orb, or long-press on a row.
- **Voice mode** is a full-screen layer over anything (hold mic, "Hey Argus", orb, QS tile).
- **Computer viewer** is a full-screen black layer (AT `#cv-full`, BH viewer).

**[D] Why four modes, not AM's Home | Chat | Projects:** AT's Bots | Chat split is the base; *Life* gives the "ecosystem of specialists for every part of a life" a home of its own (and absorbs AM Home's panels + Projects); *Desk* collects BH's Tasks/Routines/Library and AT's jobs + compose so threads stay light. Projects moves to Desk (and is surfaced in Life).

### 3.3 The team: how life-domain specialists are presented

**Default roster (seeded on first run, each optional; names are suggestions for Specialist 5 to finalise in `05-agent-ecosystem.md`):**

| Domain | Lead (default name) | Orb / glyph | Owns (features routed here) |
|---|---|---|---|
| — | **Manager** (ARGUS, locked) | cobalt "A" sphere, Lead badge | routing, delegation, bot CRUD, user profile, check-ins, daily suggestions, Chat mode |
| Health | **Vita** | green · `heart-pulse` | habits, sleep/steps (Health Connect), meds reminders, Safe Walk, doctor booking |
| Money | **Ledger** | amber · `wallet` | Money Radar, Scam Shield, bills/subscriptions, Bank mode, wallet/cards (view only), budgets |
| Work | **Chief of Staff** | indigo · `briefcase` | email/calendar/Slack via connectors, meetings (Call Copilot), drafts, jobs for others, Seller pack |
| Home | **Hearth** | cyan · `house` | groceries, home services, devices, alarms/timers, family logistics |
| People | **Muse** | pink · `users` | the People graph, birthdays, keep-in-touch nudges, Group Brain (group chats), notification replies, gifts |
| Learning | **Tutor** | violet · `graduation-cap` | Learn/flashcards, Google Classroom, study plans, student pack |
| Travel | **Atlas** | blue · `plane` | trips, flights/hotels, maps, ride booking, travel docs |
| Create | **Studio** | red · `palette` | images, Design (3D/sites/diagrams), music/Beat Lab, video/Clip Studio, Gallery, brand kit |
| Mind | **Journal** | silver · `notebook-pen` | life logs, brainstorm, Time Capsule, memory graph, reflections |
| Phone | **Hand** | grey · `smartphone` | phone control, Magic Hand, app store/site apps, settings toggles, device doctor |
| (specialists) | Coder, Researcher (X search + web), Designer, Reviewer, custom | any | Ubuntu workspace, code review, cloud computer, browser |

Presentation rules:
- **Team mode** shows leads as normal chat rows (AT) with a 3 dp domain tick on the orb badge; **domain chips** filter the list (a lead + its specialists + groups with them).
- A lead's **specialists nest under it**: in the roster (§4.2) as an indented sub-list and in the **Compose graph** as "reports to" edges (AT dotted lead edges). The Manager sits at the root.
- **Hiring**: `+ → New bot` offers *Blank*, *From a template* (AM Specialists: Coder, Designer, Researcher…, AT demo team, BH "Personal assistant / Teammate"), or *Describe it* ("a bot that watches my rent and electricity bills") → Manager drafts soul + apps + routines and shows an approval card.
- **Domain page** (Life → tile): the lead's orb hero, live panels, the domain's routines, specialists, recent files, and a "Message Vita" composer. It is a *view onto* the same thread/jobs/routines, not a separate store.
- **Group chats** (BH rooms, `create_group`/`post_to_group`): a row with stacked orbs (max 3) — e.g. "Trip to Cape Town · Atlas, Ledger, Muse".

### 3.4 Entry points

**A. The Composer grammar** (one parser: on-device Needle 3 first, then the gateway — AT behaviour, extended):

| You type | What happens | From |
|---|---|---|
| plain text | Team mode: Manager routes (or applies a command); in a thread: that bot | AT, AM |
| `@nova …` | send to Nova from anywhere; `@vita @ledger …` = group turn | AT home box mentions, BH groups |
| `/command` | slash command palette (MentionPopup) — list below | AM SlashCommands, new |
| `$ cmd` | run in the bot's Ubuntu workspace, output as tool card | AT `WS.chatRun` |
| `#file` | attach a Library file | new |
| "add job send the deck Friday" / "every weekday 9am…" | Needle → command → "Here's what I'll do" sheet or `2 changes` note | AT CMDISH + interpret |
| hold mic | push-to-talk dictation; release sends | AM, BH dictation |
| tap mic (empty field) | Voice mode with this bot | BH call, AM call |
| `+` | Add-to-chat sheet: Photo · Camera (Argus Camera modes) · File · Screen (Magic Hand grab) · Location · New job · New routine · Ask a bot (handoff) · Private chat toggle | AT attachSheet, AM + menu |

**Slash commands** (palette groups): *Go* `/team /chat /life /desk /inbox /library /routines /jobs /computers /people /settings /views` · *Make* `/bot /job /routine /group /project /skill /capsule` · *Do* `/search /x <query> /browse <url> /camera /scan /draw /design /music /terminal /run` · *Bot* `/soul /memory /engine /apps /computer /voice /fresh (new conversation) /compact /handback` · *System* `/update /health /doctor /sync /backup /private /clear /phoneqa`. Unknown `/name` falls through to AM `open_view` (every AM view stays reachable by name: Design, Memory, Learn, Seller, Jobs, Schedule, Agents, Accounts, Skills, Gallery, Browser, Doctor, Usage, Voice, Desktop, Music, Video, Holding, Look…).

**B. `+` (top bar) — the New menu** (AT plusSheet, extended; MenuRow list): New bot · New group · New job · New routine · New project · Connect an app · Open terminal · Compose graph · Scan / Camera · Settings.

**C. Long-press map**

| On | Opens |
|---|---|
| Bot row / pin / orb | Quick sheet: Open · Call (voice) · Watch computer · Pin/Unpin · Mute topics · Memory · Edit soul · Delete (danger, asks; Manager locked) — AT long-press sheet |
| Group row | Members · Rename · Leave/Delete |
| Message (agent) | Copy · Quote/reply · Read aloud · Save to memory · Save to Library · Make a job · Share · Ask another bot · Regenerate |
| Message (user) | Copy · Edit & resend · Delete |
| WorkChip / step | Copy log · Re-run step · Report |
| Approval card | Approve · Deny · Always allow · Edit first |
| Composer mic | Voice settings (voice, speed, language) |
| Composer + | Last-used attachment directly (camera) |
| `me` avatar | Toggle light/dark (AM Space) |
| Notch handle | Jump straight to Voice mode |
| Domain tile | Hide domain · Reorder · Change lead |
| Library file | Open · Share · Rename · Move to project · Send to bot · Delete |
| Person (People) | Message via bot · Who helps with them · Notes · Remind me |

**D. System entry points (Android)**

| Entry | Behaviour | From |
|---|---|---|
| **"Hey Argus"** wake word | Voice mode with the Manager; "Hey Argus, ask Vita…" routes | AM WakeWordService |
| **Assist gesture** (default assistant, `ASSIST`/`VOICE_COMMAND`) | Compact assistant panel over the current app (AM AssistPanel) with screen context; bot switcher row | AM |
| **Floating orb** (overlay) | Draggable 56 dp orb of the current bot: tap = compact chat, hold = talk, drag to edge = Magic Hand | AM Overlay / Mascot |
| **Magic Hand** | circle/grab anything on screen → copy, translate, search, save, **send to a bot** | AM |
| **Quick Settings tile** | "ARGUS" tile: tap = Voice mode; long-press = app; second tile **"Approvals (n)"** opens Inbox | AM BlazeTile + new |
| **Home-screen widgets** | (1) *Team* 4×2: Need-you count + 4 pinned orbs (tap = thread) + ask field; (2) *Ask* 4×1: composer pill → Voice/Chat; (3) *Today* 2×2: next routine/event + approvals; (4) *Live screen* 2×2: a bot's last frame when its computer runs | AM BlazeWidget (extend, Glance) |
| **Share sheet** (`SEND`) | "Share to ARGUS" → bot picker sheet (orbs, last-used first, "Manager decides") + optional note; Direct Share targets = top 4 bots | AM SEND filters + new |
| **Launcher shortcuts** | long-press icon: New chat · Voice · Inbox · top pinned bot | new |
| **Notifications** | per-bot channel, the bot's own orb as large icon (AT notify), inline reply, **Approve/Deny actions**, edge glow over other apps (opt-in overlay) | AT notify, AM NotificationBrain |
| **Deep links** | `argus://bot/<id>`, `argus://inbox`, `argus://computer/<id>`, `argus://routine/<id>`, `argus://library/<file>` | new |
| **Desktop / web** | Argus Link (QR pair, ask_pc), AT `argus-agents-api` (REST + MCP) so Desktop/Hermes/any MCP client sees the same team; BH dashboard → AM local dashboard | AM, AT, BH |
| **Lock screen** | voice + approvals require unlock for writes; read-only summaries allowed (setting) | new |

### 3.5 Feature → location table (every feature found in the three repos)

Legend — **From**: AM Argus-mobile · AT A-team · BH Bot HQ · N new. **Home** = primary place; **Also** = other entry points. "Thread" means the owning bot's chat.

#### Team, bots and conversation
| Feature | From | Home | Also |
|---|---|---|---|
| Bots as chats (home list, previews, unread, times) | AT | Team mode | Spotlight, widget |
| Built-in Manager (locked lead, routes, creates/updates bots, asks before delete) | AT | Team → Manager row; Chat mode | `@manager`, "Hey Argus" |
| Plain AI chat with the Manager (Talk mode, centred greeting) | AT | Chat mode | Notch, `/chat` |
| One continuous conversation, rich replies, follow-up chips | AM | Chat mode | — |
| Private chat (ghost: nothing saved/learned) | AM | Chat mode top bar ghost toggle | `+` sheet, `/private` |
| Needs-you banner (overdue / high-priority today) | AT | Team mode | widget, QS tile |
| Pinned bots | AT | Team mode pins | long-press, launcher shortcuts |
| Search bots and jobs | AT | Spotlight | top bar search |
| Spotlight (apps, actions, settings, chats, memory, web) | AM | top bar search | `/search` |
| Create / edit / delete bot (name, role, email, portfolio, face, colour) | AT, BH | `+ → New bot`; Bot profile → Soul | Manager by text |
| Bot look: 6 orb faces × any colour | AT | Bot profile → Soul → Look | — |
| Bot label ("Research"), about, instructions, effort, "used as" Personal/Teammate | BH | Bot profile → Soul | — |
| SOUL.md (editable soul file) | BH (engine.ts) | Bot profile → Soul → *Soul file* editor | `/soul` |
| Personas & Halo Persona Studio (sliders, language mix, boundaries) | AM | Bot profile → Soul → Personality | Settings → Team defaults |
| Duo (two companions in one chat, `@blaze`/`@halo`) | AM | generalised as **Groups** (any 2+ bots) | `@a @b` |
| Meeting room (bots debate, verdict lands in chat) | AM | Group thread → "Room" toggle (bots-only discussion view) | HandoffCard → Open |
| Bots talking to bots (`send_to_bot`, delegate) | BH, AT | HandoffCard in both threads; **Rooms** list in Desk → Inbox filter "Team talk" | People graph (bot↔bot edges) |
| Groups (create_group, post_to_group, members) | BH | Team list rows; `+ → New group` | `@a @b` |
| Make primary / new conversation (fresh) / compact / handback | BH | Bot profile → Overview actions | `/fresh /compact /handback`, long-press |
| Interrupt / Stop a turn | BH, AT | Composer stop ■ | LiveScreenCard Stop, notification |
| Step budget, "go on" continue | AT | WorkChip (stopped) → Go on | type "go on" |
| Live activity line + "Worked for · steps" chip | BH, AT | Thread | — |
| Grouped tool steps (edits, reads, commands, cloud, browser, memory, team) | AT | WorkChip expanded | — |
| Tool cards with Undo | AT | Thread | — |
| Code review gate (static + reviewer, severity) | AT | ReviewCard in thread | Desk → Jobs (job detail) |
| Guard (unbacked claims, loop stop, completeness gate, refusals) & AM FakeCall/ClaimGuard | AT, AM | invisible; surfaces as SystemNote "I couldn't verify…" | Settings → Diagnostics |
| Attachments (photo, camera, file → /root/uploads), save/attach tools, working set | AT | Composer `+`; FileCard in thread | Library |
| Markdown, tables, code blocks with copy | AT, AM | Thread | — |
| Rich blocks (diagram, chart, compare, mini tools, forms, HTML-lite) | AM | Thread → floating window | Library (saved) |
| Secure form (logins never reach the model) | AM | Thread card | Settings → Privacy → Saved logins |
| Message actions (reactions, quote, swipe-to-quote, read aloud, selection actions) | AM | long-press message | — |
| Question cards (`ask_user`) | BH | Thread | Inbox |
| Draft cards | BH | Thread | Inbox |
| Model note ("answered by …") | AT | ThreadIntro engine pill; message long-press → Details | — |
| Demo team | AT | Empty Team state | Settings → Team |
| Unassigned jobs | AT | Team list row; Desk → Jobs filter | — |

#### Work: jobs, tasks, routines, approvals, library, projects
| Feature | From | Home | Also |
|---|---|---|---|
| Jobs (due, priority, deps/waits-on, assignee, notes, status) | AT | Desk → Jobs | JobCard in thread, `+ → New job`, Needle text |
| Bot tasks (`create_task`, `update_task`, task list per bot) | BH | Desk → Jobs (same model, "by bot" filter) | Bot profile → Overview "Tasks" |
| Jobs that run (queue, waiting, notifications) | AT | Desk → Jobs (status) | Thread system notes |
| Background jobs (long commands detached, resume on finish) | AT | Desk → Computers → Jobs running | Thread tool card "background job" |
| Job failure log | AT | Job detail sheet | — |
| Needs your ok (overdue: Tomorrow / Done) | AT | OkCard in thread; Desk → Inbox | Need-you banner |
| Approvals for write actions (Composio) | BH | **Desk → Inbox**; ApprovalCard in thread | bell sheet, notification actions, QS tile, widget |
| Manager confirmations (delete bot, spend, send, cloud start) | AT | Inbox + thread | notification |
| Browser confirm (pay, book, send, submit, delete) | AT, AM | Inbox + thread | — |
| ConfirmGate / ask-before-risky (AM) / Autopilot exceptions | AM | Inbox | Settings → Privacy → Approvals |
| Permissions policy per tool (always allow / ask / never) | BH | Settings → Privacy → **Approvals & permissions** | ApprovalCard "Always allow" |
| Connect-app requests (`request_app_connection`) | BH | ConnectCard in thread; Inbox | Settings → Apps |
| Routines (once/daily/weekly/cron, local TZ, fire with app closed) | AT, BH, AM | Desk → Routines | Bot profile → Routines, domain page, `/routine`, Needle text |
| Event routines (Composio triggers: new Gmail, Slack, GitHub PR) | BH | Desk → Routines → "When…" | Bot profile → Routines |
| AM Triggers & Autopilot routines (arrive at campus → silent + timetable) | AM | Desk → Routines → "When…" (device triggers group) | Phone lead |
| Run routine now / pause / edit | BH | Routine row actions | — |
| Proactive check-ins (times, stay_silent, back-off) | BH | Settings → Notifications → Check-ins; Bot profile (Manager) → Routines | `/checkins` |
| Daily suggestions (≤3, quiet hours, daily cap, per-topic mutes) | AT | Team (Manager row preview) + Inbox "Suggestions" | Settings → Notifications |
| Jarvis mode (questions, heads-ups while phone in use) | AM | Settings → Notifications → Proactivity level (Off / Quiet / Chatty / Jarvis) | — |
| Habits detection (offers to automate) | AM | Inbox suggestion → routine | Phone lead |
| Follow-ups | AM | Inbox | thread |
| Alarms, timers, scheduled commands | AM | Desk → Routines (Clock group) | `/alarm`, Home lead |
| Schedule view (day plan) | AM | Life → Today strip; Desk → Routines → Calendar view | `/schedule` |
| Library (files bots save, `save_file`, list, download) | BH | **Desk → Library** | Bot profile → Library tab, `#file` |
| AT workspace files (stat/read/zip/save to Downloads/share) | AT | Desk → Library → "Workspaces" | FileCard |
| Gallery (images/media) | AM | Desk → Library → Media | Create lead, `/gallery` |
| Projects (threads, brief ≤600 tokens, instructions, docs search) | AM | Desk → Projects | Life → Projects, `/project` |
| Compose graph (drag & connect team: owns, waits-on, reports-to; live status) | AT | **Team roster → Graph view** | `+ → Compose graph` |
| Undo center | AM | Toast "Undo"; Settings → Diagnostics → Recent actions | — |

#### Computers, workspace, browser, terminal
| Feature | From | Home | Also |
|---|---|---|---|
| Per-bot Ubuntu 24.04 workspace (PRoot), tmux window per bot | AT | Bot profile → Computer → Workspace | `$ cmd`, Terminal |
| AM Alpine Linux terminal, run_code, git, preview servers | AM | Desk → Computers → Terminal (one Linux runtime **[Q]** see §7) | `/terminal` |
| Terminal view (PTY, tabs, extra keys) | AT, AM | full-screen Terminal | `+ → Open terminal` |
| Per-bot computer screen (Orgo screen, dressed desktop, dock, home page "Welcome back, Nova") | BH | Bot profile → Computer; Desk → Computers | LiveScreenCard |
| Watch live (side panel → phone: card + full viewer) | BH, AT | LiveScreenCard → **Computer viewer** | widget, notification "Nova is browsing" |
| Take over / hand back (mouse+keyboard → touch, keyboard) | BH | Computer viewer → Take over | `/handback` |
| Cloud computer (Orgo / Codespaces, lease, idle stop, hard cap, extend, minutes, cost) | AT | Desk → Computers → Cloud; Settings → Computers | CloudChip |
| Computer suspend/ensure, screen reset, engine screen stop | BH | Computer page actions | — |
| Bots' own browser (search/fetch/open, steps, element ids, tabs keep cookies) | AT, AM | Bot profile → Computer → Browser tabs; Desk → Computers | browser cards in thread |
| AM background browser, private tabs, VPN by country, Argus Free (WARP), "Ask about this page" | AM | Desk → Computers → Browser; Settings → Phone → Browser | Phone lead, share sheet (URL) |
| Phone browser activity / PiP | AM | Computers → Browser → "Open on phone" | — |
| Argus Link (pair Desktop, ask_pc) | AM | Desk → Computers → "Your PC" | Settings → Sync |
| Local dashboard (phone web server, share on Wi-Fi) | AM | Settings → Sync & devices → Dashboard | — |

#### Engines, models, apps, skills, memory
| Feature | From | Home | Also |
|---|---|---|---|
| Engine per bot (provider, model, effort; subscription sign-in on the bot's screen) | BH | Bot profile → Engine | ThreadIntro pill |
| Hermes engine status/restart/login | BH | Bot profile → Engine → status row | — |
| Gateway live models, automatic pick, fallback (no picker by default) | AT, AM | Settings → Models (read-only list + "Automatic") | Engine tab "Automatic" |
| Gemini with your key (per-bot model, cool-down) | AT | Settings → Models → Your keys; Bot profile → Engine | — |
| On-device Cactus Needle 3 (commands offline) | AT | Settings → Models → On-device | status in Diagnostics |
| Backup brain / offline brain, BlazeRouter, brain race | AM | Settings → Models → On-device / Advanced | — |
| Tiers Insight / Oracle / Panoptes | AM | Bot profile → Engine → Depth (Automatic default) | — |
| Connectors (ARGUS Connect: per-user apps, per tool on/off, logos, categories, test, disconnect) | AT | **Settings → Apps & connectors** (full-screen plugin list) | ConnectCard, Bot profile → Apps |
| Composio apps (connect, callback, per-bot app access) | BH | merged into Apps & connectors (provider column) **[Q]** | Bot profile → Apps |
| MCP servers (user-registered), MCP config | AM | Settings → Apps → MCP servers | — |
| X search (`x_search`, xAI) | BH | a tool every bot has; Researcher specialist; `/x` | Voice mode results |
| Skills (SKILL.md, my skills, Polish, Argus originals read-only, packs, install) | AT, AM, BH | Settings → Skills; Bot profile → Skills (per bot) | `/skill`, Manager "write a skill" |
| Hybrid memory (bot private notes, team memory) | AT | Bot profile → Memory | `/memory`, long-press message → Save |
| Bot memories (search, forget, consolidate, conversation-memory meter, compact) | BH | Bot profile → Memory | — |
| Mind memory (blaze.db rows, journal, search_chat, pinned core, subject index) | AM | You → **What the team knows** (+ Manager memory) | Mind lead, Spotlight |
| User profile kept by Manager ("What Manager knows about you") | AT | You → What the team knows | Manager profile |
| Memory graph | AM | You → What the team knows → Graph | Mind lead |
| "What Argus is holding" (pinned facts, summary, goal pin) | AM | Bot profile → Memory → Holding (per bot) | `/holding` |
| Time Capsule (sealed notes) | AM | Mind domain page | `/capsule` |
| Learned router, tool stats, traces, speed log | AM | Settings → Diagnostics | — |

#### Voice, phone, device, media, life
| Feature | From | Home | Also |
|---|---|---|---|
| Voice calls with a bot (xAI realtime voices ara/eve/leo/luna/rex/sal, speed, language, preview) | BH | **Voice mode**; Bot profile → Voice | tap mic, long-press mic |
| Hey Argus, hold orb to talk, spoken replies, Read aloud | AM | Voice mode; Settings → Voice | QS tile, orb |
| My voice (cloned, on-device PocketTTS) | AM | Settings → Voice → My voice | — |
| Gemini Live voice | AM (voice/gemini) | Voice mode engine option | Settings → Voice |
| Dictation | BH, AM | hold mic | — |
| Call Copilot (live captions, suggested answers) | AM | Work lead; Settings → Phone → Calls | ongoing-call notification |
| Phone control (run_plan, pointer, accessibility) | AM | Phone lead thread; any bot via tool | PhoneControlBanner, orb |
| Messages, calls, contacts, calendar, alarms, settings toggles, USSD | AM | Phone lead / Home lead (by tool) | any bot (approval for send/call) |
| Magic Hand (grab, watches, teach) | AM | system overlay; Settings → Phone → Magic Hand | orb drag, `/hand` |
| Argus Camera (Scan, Text, Ask, Photo, Translate, Document) | AM | Composer `+ → Camera` | `/camera`, Magic Hand, share |
| Bank mode | AM | Money lead; Settings → Privacy → Bank mode | QS? (no) |
| Scam Shield | AM | Money lead (alerts in Inbox) | Settings → Privacy |
| Money Radar | AM | Money domain page | — |
| Safe Walk | AM | Health lead ("walk me home"); ongoing notification | `/safewalk` |
| Notification replies, Group Brain, chat logs | AM | People lead / People page | Settings → Notifications → Replies |
| Life logs (mic transcription to memory), diarize, brainstorm | AM | Mind domain page | QS tile toggle **[Q]**, `/lifelog` |
| Learn / flashcards, Google Classroom, student pack | AM | Learning domain page | `/learn` |
| Seller pack (brand kit, posts, ads, store digest) | AM | Work domain page → Seller | `/seller` |
| Image generation | AM | Create lead; any thread | `/draw` |
| Design studio (3D, sites, diagrams, live preview) | AM | Create domain page → Design | `/design` |
| Music (MusicHub, Beat Lab, DJ, lyrics), Video, Movies (sources, downloads), Clip Studio | AM | Create domain page → Media; **Music/Video apps** stay separate activities | `/music /video`, widget? |
| App store, site apps (install web apps), VirusTotal scan | AM | Phone domain page → Apps | `/store` |
| Stage (chat screen as playground, stage elements, timers) | AM | Chat mode → `⋯ → Edit stage` **[Q]** (keep, power-user) | `/stage` |
| Doctor / Screen doctor / Phone QA | AM | Settings → Diagnostics | `/doctor /phoneqa` |
| Updates (versions.json, resumable APK, banner) | AM | Settings → Updates | `/update` |
| Usage, data meter, token ledger, quota | AM, AT | You → Plan & usage | — |
| Plans / billing (Basic, Standard, Pro) | AT, AM | You → Plan & usage | — |

#### Account, privacy, sync, setup
| Feature | From | Home | Also |
|---|---|---|---|
| ARGUS sign-in (Google/GitHub PKCE, email+password, 2FA, recovery, forgot) | AT, AM | Onboarding; You → Account | — |
| Use without an account (offline-first) | AT | Onboarding "Skip" | — |
| Biometric login / app lock | AM | Settings → Privacy → App lock | — |
| Vault (tokens out of storage into Keystore), saved secrets, saved logins, wallet | AT, AM | Settings → Privacy → Wallet & logins | Secure form |
| Allowed emails / owner (BH server auth) | BH | n/a on phone (account = ARGUS) | — |
| Sync (two-way team sync with `argus-agents-api`, advanced self-host URL/token) | AT | Settings → Sync & devices | — |
| Cloud sync of chats/memory/skills (AM SyncEngine, KeySync) | AM | Settings → Sync & devices | — |
| MCP endpoint of the team (`/mcp`, OpenAPI) | AT | Settings → Sync & devices → "Use your team from other apps" | — |
| Backup, import/export JSON, erase | AT, AM | Settings → Sync & devices → Backup | — |
| One-screen setup (downloads: Linux, tools, skills, brain, voice model) + later | AM | Onboarding → Setup; Settings → Diagnostics → Downloads | ProgressLine |
| Workspace first-run (Ubuntu download, nickname) | AT | merged into the same setup list | — |
| Permissions page (phone control, notifications, mic, overlay, battery) | AM | Settings → Phone & permissions | inline when needed |
| Appearance (light/dark/auto, ground, accent off by default) | AT, AM | Settings → Appearance | long-press avatar |
| Language, reply language | AM | Settings → Language | — |
| Feedback, About, licences, what's kept on this phone | AM | You → About | — |

---

## 4. Key screen specs

Phone frame ≈ 360 × 780 dp. `○` = 38 dp glass icon button, `●` = status dot, `(◉)` = bot orb, `[ ]` = button, `▔` = frosted chrome. Bottom composer is identical on every mode unless noted:

```
▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔
 ( + )  Tell the team…                 ( 🎙 )      ← 58 dp pill, 12 dp insets, mic ↔ send ↔ stop
```

### 4.1 Home — Team mode

```
┌──────────────────────────────────────────────┐
│ (F)                       ○🔍   ○🔔•   ○＋    │ top bar, frosted; F = me avatar
│                    ▂▂▂                        │ notch handle (pull → Team·Chat·Life·Desk)
│ Finn's team                                   │ display 32/750
│ 9 bots + Manager · 4 jobs today · 1 overdue   │ sub 14.5 mute, "1 overdue" red
│ [All] [Health] [Money] [Work] [Home] [People]→│ FilterChips, scroll
│ ┌──────────────────────────────────────────┐ │
│ │ ⚑  3 need you · Ledger, Atlas    Review ›│ │ NeedYouBanner (ink, glossy)
│ └──────────────────────────────────────────┘ │
│   (◉)        (◉)•        (◉)        (◉)       │ PinStrip lg orbs, red dot = overdue
│   Vita      Ledger      Atlas      Coder      │
│ (A) Manager  LEAD                    9:41    │ gold-tinted row
│     Booked Vita's check-in for 9am. 2 ideas…  │
│ (◉) Ledger                           9:12  • │ unread dot
│     1 overdue · Pay ZESCO bill               │ red preview
│ (◉◉) Trip to Cape Town           Yesterday  │ group row, stacked orbs
│     Atlas: Flights found, need your OK        │
│ (◉) Coder ● working                   8:02   │ green live dot on orb
│     Worked for 6m · 23 steps                  │
│ (?) Unassigned                                │
│     2 jobs without a bot                      │
│ ┌──────────────────────────────────────────┐ │
│ │ All jobs                         11 open │ │ .alljobs
│ └──────────────────────────────────────────┘ │
│ [＋Add job] [🧠 Memory] [⟳ Routines]          │ ChipsRow (Team variant)
│ ( + )  Tell the team…                 ( 🎙 ) │
└──────────────────────────────────────────────┘
```
Behaviour: pull-to-refresh = sync; search icon turns the title into a search field in place (AT `_hs`); empty team → serif hero "Build your team" + three orbs + [New bot] [Demo team] + "Set up my life team" (seeds domain leads after a 3-question interview). A running cloud computer adds the CloudChip under the top bar.

### 4.2 Team roster (Team → `⋯` on the title, or `/roster`)

```
┌──────────────────────────────────────────────┐
│ ‹  Team                    [List | Graph]  ○＋ │
│ ─────────────────────────────────────────────│
│ (A) Manager · Lead      routes everything     │
│ HEALTH ─────────────────────────────────────  │ section head + domain hue tick
│ (◉) Vita          ● idle    Claude · 2 apps   │ engine + app count
│    └ (◉) Sleep coach   ● waiting for you      │ specialist nested
│ MONEY ──────────────────────────────────────  │
│ (◉) Ledger        ● working  Auto · 3 apps    │
│ WORK ───────────────────────────────────────  │
│ (◉) Chief of Staff ● idle   Grok · Gmail…     │
│    ├ (◉) Inbox     ● idle                     │
│    └ (◉) Coder     ● working 🖥 live          │ computer live badge
│ …                                             │
│ [ ＋ Hire a specialist ]   [ ⤓ Templates ]     │
└──────────────────────────────────────────────┘

Graph view (AT Compose graph, full-bleed canvas):
      (A) Manager
     ╱  ┊   ┊   ╲              solid = owns job · dashed = waits on · dotted = reports to
 (Vita)(Ledger)(CoS)(Atlas)    nodes = glass boxes with orb, name, live status dot, progress bar
           ┊   ╱  ╲            drag to re-parent; tap edge → Connection sheet; pinch/pan
        [job] (Inbox)(Coder) ── ─ ─ ▶ [job]
 gtools bar: hint · legend · [Fit] [＋ Bot] [＋ Job]
```

### 4.3 Bot chat (thread)

```
┌──────────────────────────────────────────────┐
│ ‹ (◉) Atlas                            ○⋯    │ top bar thread variant
│       ● Working · Travel                      │ green status line
│ ─────────────────────────────────────────────│
│                 (◉)                           │ ThreadIntro (xl orb)
│                Atlas                          │
│        Travel · atlas@argus.ai                │
│        ( ● Auto · gateway )                   │
│ ┌ ATLAS · JOBS ────────────── 2 open ┐        │ JobCard
│ │ ☐ Find flights LUN→CPT    Fri      │        │
│ │ ☐ Hotel near V&A          Fri      │        │
│ └────────────────────────────────────┘        │
│              ┌──────────────────────────┐    │
│              │ Find flights for Jan 5,  │    │ user bubble (ink)
│              │ window seat, under $400  │    │
│              └──────────────────────────┘    │
│ ( Worked for 2m 10s · 7 steps  › )            │ WorkChip (done)
│ Found 3 options. The best is Proflight 07:10, │ AgentText, no bubble
│ $356, window 12A. Booking needs your OK.      │
│ ┃ 🛡 Atlas wants to                            │ ApprovalCard (amber edge)
│ ┃ Book Proflight PF701 · $356                 │
│ ┃ Jan 5 07:10 LUN→CPT · seat 12A · Visa ··42  │
│ ┃ Browser · pays money                         │
│ ┃          [ Deny ]  [ Approve ]   ⋯           │ Approve on thumb side
│ ┌ ● Live · Atlas's computer ─────────────┐    │ LiveScreenCard
│ │ "Opening proflight.co.zm…"             │    │
│ │ ┌────────────────────────────────────┐ │    │
│ │ │          16:10 live frame          │ │    │
│ │ └────────────────────────────────────┘ │    │
│ │ [Watch]  [✋ Take over]  [■ Stop]       │    │
│ └────────────────────────────────────────┘    │
│ ◌ Checking hotels near V&A · 3 steps   1:24 ⌄ │ ActivityLine (live)
│   ● Searching the web   "V&A hotels"          │  step list (open)
│   ● Reading a page      booking.com           │
│   ◌ Comparing prices                          │
│ [＋Add job] [🧠 Memory] [⟳ Routine] [🖥 Screen]│ ChipsRow (thread)
│   [📞 Call] [✎ Soul]                           │
│ ( + )  Message Atlas                  ( ■ )  │ stop while working
└──────────────────────────────────────────────┘
```
Rules: the thread scrolls under the frosted composer; new content auto-follows only when you're at the end (AT `atEnd`); a "↓ 2 new" pill appears otherwise. A HandoffCard appears when Atlas asks Ledger something ("Atlas → Ledger: Is $356 within the travel budget?" · ✓ "Yes, $500 left").

### 4.4 Bot profile (`⋯` in thread, tap orb, long-press row → Open profile)

```
┌──────────────────────────────────────────────┐
│ ‹                                    ○⋯       │
│                  (◉)                          │ hero orb 120, Working ring
│                 Atlas                         │ title2
│        Travel · reports to Manager            │
│   ● Computer awake · Auto (gateway) · 4 apps  │ status line (BH)
│  [💬 Message] [📞 Call] [🖥 Watch] [⏸ Pause]   │ 4 action discs
│ [Overview][Soul][Memory][Skills][Apps][Engine]│ scrollable tab row (seg)
│ [Computer][Routines][Voice][Connections]      │
│ ─────────────────────────────────────────────│
│ OVERVIEW                                       │
│ ┌ Live screen (thumb) ──────────────┐ Open ›   │ (BH details panel top)
│ UPCOMING                          Manage       │
│ ⏱ Daily fare check · Weekdays 9:00   (●━)      │ routine toggle
│ TASKS · 2 open      LIBRARY · 5 files          │
│ SUGGESTED                          Hide        │
│ ⧉ Connect Google Calendar              ›      │
│ CONVERSATION  [New conversation] [Compact]    │
│ DANGER  [Make primary] [Delete Atlas]         │
└──────────────────────────────────────────────┘
```

| Tab | Contents | From |
|---|---|---|
| Overview | live screen thumb, upcoming routines (toggles), tasks, library count, suggested (connect app), conversation actions (fresh, compact), make primary, delete (danger, confirm; Manager locked) | BH DetailsPanel + BotSettings General |
| Soul | Name, label, about, **instructions**, effort (Low/Medium/High/Max), used as (Personal/Teammate), look (6 faces × colour), domain, reports-to; **Soul file** (SOUL.md monospace editor with Preview, Revert, history); **Personality** sliders (warmth, humour, playfulness, formality, length), language mix, boundaries | BH, AT, AM Halo Studio |
| Memory | search, private notes (add/edit/forget), team notes visible to this bot, Holding (pinned facts, goal pin), conversation-memory meter ("62 % full — older messages summarise automatically"), Consolidate, memory files (MEMORY.md / USER.md view-edit for Hermes bots) | AT memory.js, BH, AM |
| Skills | this bot's skills (create, edit, delete, Polish), shared skills toggles | BH, AT |
| Apps | connectors this bot may use (on/off per app and per tool), Connect new, write actions → "asks first / always allowed" | AT connect.js, BH |
| Engine | Automatic (default) or provider + model, depth tier, Gemini key model, subscription sign-in (opens Computer viewer on the bot's screen), Hermes status (ready/restart), fallback note | BH EngineSettings, AT, AM |
| Computer | screen (live thumb, Open viewer, Reset screen, Suspend), Ubuntu workspace (open terminal at the bot's tmux window, disk use), cloud computer (provider, idle minutes, used minutes/cost), browser profile (tabs, Clear cookies) | BH, AT |
| Routines | the bot's schedule + event routines + check-in times (Manager) | BH, AT |
| Voice | voice (Ara, Eve, Leo, Luna, Rex, Sal / My voice), speed, language, Preview, Start call | BH VoiceSettings, AM |
| Connections | **people** this bot serves (from People graph), **bots** it talks to (frequency, last handoff), groups it's in, reports-to/manages | N |

### 4.5 Computer viewer / take-over

```
┌──────────────────────────────────────────────┐  black, full screen, immersive
│ (◉) Atlas's computer · Awake        ✕        │  BH header
│ ● Live  ⟳  ▾tabs                              │
│ ┌──────────────────────────────────────────┐ │
│ │                                          │ │  frame, fit-width; pinch zoom 1–4×,
│ │       (landscape: rotate for full)       │ │  double-tap zoom to point
│ │                                          │ │
│ └──────────────────────────────────────────┘ │
│ "Clicking 'Select seat'…"                    │  action line (AT)
│ ─────────────────────────────────────────────│
│  WATCHING                                     │
│  [ ✋ Take over ]        [ ■ Stop bot ]        │  bottom bar, thumb zone
│                                               │
│  TAKEN OVER (bot paused, amber frame border) │
│  [⌨ Keys] [⇥ Tab] [Esc] [⌫] [🖱 Right-click]  │  extra keys row (AT terminal style)
│  touch = mouse: tap click · long-press right  │
│  · two-finger scroll · drag select            │
│  [ ↩ Hand back to Atlas  + note… ]            │  primary ink; note goes into thread
└──────────────────────────────────────────────┘
```
Rules: frames only poll while visible (AT); Take over pauses the turn (BH interrupt) and shows an amber 2 dp border + "You're in control" chip; Hand back resumes with an optional note ("I picked 12F"); secure fields in the frame are masked when the bot is in control. Landscape supported only here and in Terminal/Media.

### 4.6 People — social graph (Life → People, `/people`, Muse's home)

```
┌──────────────────────────────────────────────┐
│ ‹  People                  [Graph | List] ○＋ │
│ ┌──────────────────────────────────────────┐ │
│ │          (Mum)                           │ │  graph: You at centre (avatar),
│ │            ╲     (Tumelo)                │ │  people as photo/initial circles
│ │   (Sara)───(F)───(Dad)                    │ │  (no glow = humans), distance =
│ │            ╱  ┊                          │ │  closeness; ring segments = which
│ │      (Alex)   ┊ (◉)Muse (◉)CoS            │ │  bots help with them; bots shown
│ └──────────────────────────────────────────┘ │  small on dotted edges
│ KEEP IN TOUCH                                 │
│ (S) Sara · 3 weeks since you talked  [Nudge] │  Muse suggestions
│ (M) Mum · birthday in 4 days       [Gift ›]  │
│ GROUPS                                        │
│ 👥 Family · 300 unread → "Catch me up"        │  Group Brain
│ BOTS TALKING                                  │
│ (◉)Atlas ⇄ (◉)Ledger · 4 today        ›       │  bot↔bot rooms (read-only + drop in)
└──────────────────────────────────────────────┘
Person sheet: avatar · how you know them · notes (memory) · key dates · channels
(phone, WhatsApp, email) · "Bots that help": Muse (birthdays), CoS (email) ·
[Message via …] [Remind me] [Forget person] (danger)
```
Data: people come from contacts (permission), memory, connectors (email/Slack), notification logs (opt-in). Everything is on-device by default; "Forget person" removes notes + edges.

### 4.7 Routines & tasks (Desk → Jobs / Routines)

```
┌──────────────────────────────────────────────┐
│ (F)   Desk                    ○🔍  ○🔔  ○＋    │
│ [Inbox 3][Jobs][Routines][Library][Projects]→ │  seg (scroll), counts
│ JOBS                                          │
│ [Today 4][Upcoming][Overdue 1][Done][By bot] │
│ ▌☐ Pay ZESCO bill        (◉)Ledger   Yesterday│  red due, 4 dp priority tick
│ ▌☐ Send deck to Alex      (◉)CoS      Today   │
│  ☐ Book hotel  · waiting  (◉)Atlas    Fri     │  waits-on shown
│ ROUTINES  (Routines tab)                      │
│ TODAY ───────── 06 ─ 09 ─ 12 ─ 15 ─ 18 ─ 21   │  day timeline, now-line
│        ⏱Vita  ⏱Ledger digest   ⏱CoS check-in  │
│ ⏱ Daily digest · Weekdays 9:00 · (◉)CoS (●━)  │  schedule row: toggle, ⋯ Run now
│ ⚡ When a Gmail arrives from "bank" → Ledger   │  event routine (BH triggers)
│ 📍 When I get to campus → silent, timetable   │  device trigger (AM Autopilot)
│ ⏰ Alarm 06:30 · weekdays                      │  clock group
│ 💬 Check-ins 09:00 · 16:30 (Manager)          │  proactive check-ins
│ ( + )  "every Friday 5pm, Ledger sends…"  🎙  │  composer creates by text
└──────────────────────────────────────────────┘
```
Routine editor sheet: *What* (prompt) · *Who* (bot picker) · *When* (Time / Repeat / Cron / Event: app trigger, location, battery, Wi-Fi, notification) · *Deliver* (thread / notification / silent unless something found) · *Ask first?* · [Run now] [Save].

### 4.8 Library (Desk → Library)

```
┌──────────────────────────────────────────────┐
│ [Inbox][Jobs][Routines][Library][Projects]   │
│ 🔍 Search files                               │
│ [All][Docs][Images][Code][Media][Workspaces] │
│ RECENT                                        │
│ 📄 cape-town-itinerary.md  (◉)Atlas · 2h · 4KB│  FileCard rows
│ 🖼 logo-v3.png            (◉)Studio · 1d      │
│ </> budget.py             (◉)Coder · /root/…  │  workspace file
│ BY BOT    (◉)(◉)(◉)(◉)(◉)  →                 │  orb strip filters by bot
│ BY PROJECT  ▸ Cape Town trip (6) ▸ ARGUS (14) │
│ Gallery ›                    Downloads ›      │  AM Gallery link
└──────────────────────────────────────────────┘
File sheet: preview (image / HTML sandbox / text / code with highlight) ·
Open · Share · Save to Downloads · Send to bot · Move to project · Delete
```

### 4.9 Approvals inbox (bell sheet; Desk → Inbox full page)

```
┌──────────────────────────────────────────────┐
│ Inbox                         [Select] ✓ All  │
│ [All 5][Approvals 3][Questions][Connect][Failed][Suggestions]
│ ┃🛡 (◉)Atlas · 2m       Book PF701 · $356     │  pays money → amber, biometric
│ ┃   Jan 5 · seat 12A · Visa ··42              │
│ ┃   [Deny]                [Approve 🔒]         │
│ ┃🛡 (◉)CoS · 10m        Send email to Alex     │
│ ┃   "Hi Alex, quick question about seats…"    │  preview 3 lines, tap = full draft
│ ┃   [Edit] [Deny]               [Approve]     │
│ ? (◉)Vita · 1h  Which gym days work?          │  question
│   [Mon/Wed/Fri] [Tue/Thu] [Reply…]            │
│ ⧉ (◉)Muse · 3h  Needs WhatsApp to send wishes │  connect
│   [Not now]                   [Connect]       │
│ ✕ (◉)Coder · job failed after 3 tries   Log ›│  failure
│ ✦ Manager suggests: automate your 7am news?   │  suggestion → routine
│   [Mute topic]  [No]           [Yes, set up]  │
└──────────────────────────────────────────────┘
```
Rules: sorted by urgency (money/irreversible first, then age). Swipe right = Approve only for low-risk kinds (send message to a known contact); money/delete/post never swipe-approve — they need the button **and** biometric (Settings). Every decision writes back into the owning thread. Bulk approve only within one bot and one kind.

### 4.10 Voice mode

```
┌──────────────────────────────────────────────┐  ground darkens; orb is the UI
│ ⌄                                 Eve · 1.0× │  collapse / voice chip
│                                               │
│                  (  ◉  )                      │  hero orb 200 dp, body pulses with
│                                               │  amplitude (Talking state);
│                  Atlas                        │  swipe orb ←/→ = switch bot
│              Listening…                       │
│                                               │
│  "Find me a cheaper flight on the 6th"        │  live captions (you)
│  Searching X and the web for fares…           │  bot captions; tool line
│ ┌ 𝕏 3 posts · Proflight sale Jan 6 ┐          │  result cards (X search) stack
│                                               │
│  (◉)(◉)(◉)(◉)  ← who's on the call (group)    │
│                                               │
│  [ 🔇 ]      [ ⏹ End ]      [ ⌨ Type ]         │  bottom bar; End = big red disc
│  [ ⇢ Hand to chat ]  (opens thread with summary)
└──────────────────────────────────────────────┘
```
Rules: hold-to-talk or open-mic toggle; barge-in interrupts the bot; real work is handed to the same bot with the same memory/tools (BH rule) and appears in its thread; approvals spoken *and* shown, but approval needs a tap (never voice-only for money/delete). Lock-screen voice: read-only.

### 4.11 Settings (avatar → You)

```
┌──────────────────────────────────────────────┐
│ ‹                                             │
│ Finn                                          │  display
│ argusagent7@… · Pro                           │
│ ┌ Plan & usage ────────── Pro · 12 days ›  ┐  │  plan card (serif italic plan name)
│ ┌──────────────────────────────────────────┐ │
│ │ 🧠 What the team knows               ›   │ │  profile + Mind memory + graph
│ │ 👥 People                            ›   │ │
│ │ 🛡 Approvals & permissions      2 rules › │ │
│ └──────────────────────────────────────────┘ │
│ ┌ TEAM ────────────────────────────────────┐ │
│ │ 🤖 Team & bots (defaults, templates, demo)│ │
│ │ ⧉ Apps & connectors · 6 on           ›   │ │
│ │ ✦ Skills                              ›  │ │
│ │ ⚙ Models & engines · Automatic        ›  │ │
│ │ 🖥 Computers & workspace · Cloud off   ›  │ │
│ └──────────────────────────────────────────┘ │
│ ┌ YOU & PHONE ─────────────────────────────┐ │
│ │ 🎙 Voice (Hey Argus, voices, My voice) › │ │
│ │ 🔔 Notifications & proactivity        ›  │ │  quiet hours, check-ins, Jarvis, mutes
│ │ 📱 Phone & permissions (control, Magic   │ │
│ │    Hand, orb, browser, calls)          › │ │
│ │ 🔒 Privacy & security (app lock, wallet, │ │
│ │    logins, Bank mode, Scam Shield)     › │ │
│ │ 🎨 Appearance · Dark · Plain ground    › │ │
│ │ 🌐 Language                            › │ │
│ └──────────────────────────────────────────┘ │
│ ┌ SYSTEM ──────────────────────────────────┐ │
│ │ ⟳ Sync & devices (sync, MCP, PC link,     │ │
│ │   dashboard, backup/import/export)     › │ │
│ │ ⤓ Updates · 1.0.0                      › │ │
│ │ 🩺 Diagnostics (doctor, downloads, logs)›│ │
│ │ ℹ About & feedback                     › │ │
│ └──────────────────────────────────────────┘ │
│ [ Sign out ]                                  │
│ (🔍 Search settings)                          │  floating search pill (AT .cnp-search)
└──────────────────────────────────────────────┘
```
Every page reachable by name (`/settings voice`, Spotlight, ask a bot) — AM "open by name" rule kept.

### 4.12 Life mode (domains)

```
┌──────────────────────────────────────────────┐
│ (F)                       ○🔍   ○🔔   ○＋      │
│ Wednesday — three things today.               │  serif day line
│ TODAY  06 ──●now── 12 ──── 18 ──── 24         │  timeline: events, routines, check-ins
│        Gym  ⏱digest  Call Mum  ⏱bills          │
│ ┌──────────────┐ ┌──────────────┐            │  domain tiles (Surface glass)
│ │(◉) Health    │ │(◉) Money     │            │
│ │ 6.2k steps   │ │ K1,240 week  │            │  one live value
│ │ Gym at 17:00 │ │ 1 bill due ⚠ │            │  one line
│ └──────────────┘ └──────────────┘            │
│ │(◉) Work │ │(◉) Home │ │(◉) People│ …       │  2-col grid, reorderable
│ PEOPLE   (M)(S)(A)(T)(D)        See graph ›   │
│ PROJECTS ▸ Cape Town trip  ▸ ARGUS overhaul   │
│ PANELS   ☁ 24° Lusaka · Latest answer · Ask…  │  AM Home cards
│ ( + )  Ask about your life…           ( 🎙 ) │
└──────────────────────────────────────────────┘
```

### 4.13 Chat mode

```
┌──────────────────────────────────────────────┐
│ (F)                          ○👻   ○✎          │  ghost = private chat, ✎ = new chat
│                                               │
│                  (A)                          │  Manager sphere (cobalt glow)
│        Evening. What's on your mind?          │  serif 30
│                                               │
│  [Plan my week] [What did I miss?] [Scan…]    │  suggested asks (AM)
│ ( + )  Ask anything                   ( 🎙 ) │
└──────────────────────────────────────────────┘
```
Once messages exist it's AM's chat (rich replies, droplet stream, follow-up chips) in AT's no-bubble agent text. When the Manager delegates, a HandoffCard shows which bot took it ("Atlas is on it →").

