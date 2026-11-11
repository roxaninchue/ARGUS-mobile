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

