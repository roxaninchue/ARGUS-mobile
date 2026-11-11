# 03 · Argus-mobile inventory, architecture & merge map

> Slice 3 of the ARGUS overhaul (see `overhaul.md`). Author: Specialist 3 (Hark), 2026-11-11.
> Source read: `argus-mobile@main` as pulled for the overhaul (versionName **0.52.3**, versionCode **190**, plus three
> unreleased rounds on top: always-Autopilot, one look from `ui/space/`, icons everywhere). A-team and Bot HQ were
> skimmed from their README / HANDOFF / ARCHITECTURE only; slices 1 and 2 own their full inventories.
> Status: **complete first pass** (sections a–d). Nothing here was built or run (owner rule: no build).

Package root everywhere below: `app/src/main/java/com/blaze/assistant/` (written as `…/`).
Size of the app: **1,218 Kotlin files**, `agent/` alone 247 files / ~40k lines (`Agent.kt` 5,807 lines), `ui/` 167 files / ~37k
lines; 451 JVM test files, 3 androidTest files; 57 bundled skills, 10 built-in specialists.

---

## (a) Feature inventory (with package paths)

### a.1 Shell, navigation, look
| Feature | What it does | Where |
|---|---|---|
| Space shell (current UI) | Top bar = round button (+ / search / menu per tab) · **Home \| Chat \| Projects** pill · avatar (tap = profile, long-press = light/dark); Earth-from-space backdrop; floating "Message" composer | `ui/space/SpaceTopBar.kt`, `SpaceTheme.kt` (SpaceLook, SpaceType serif headlines, SpaceCard, SpaceBackdrop), `SpaceLogic.kt` (pure: palette, tabs, pill fit, greeting, card order, profile rows, settings tree, onboarding steps, test tags), `SpaceRows.kt`, `SpaceOverlay.kt` |
| Home | Card stack: greeting, what needs you (sign-in / setup), latest answer, recent projects (≤3), suggestions, new chat | `ui/space/SpaceHome.kt` (`SpaceNav`, `SpaceHomeState`); legacy unreachable board: `home/*`, `ui/HomeBoardUi.kt`, `HomeGridUi.kt`, `HomeIntro.kt` (deletion pending) |
| Chat | Messaging-style bubbles, one work/task pill per answer, agent pills, quote chips, selection pill, reactions, read receipts, `/clear` + Undo, private (ghost) chat | `ui/ChatScreen.kt` (1,648 lines), `ui/ChatBubbles.kt`, `Bubbles.kt`, `TaskPill.kt`, `AgentPill.kt`, `SelectionPill.kt`, `SwipeToQuote.kt`, `DeliveryStatus.kt`, `agent/ClearScreen.kt`, `agent/PrivateChat.kt`, `agent/ChatNav.kt` |
| Projects | Name, instructions, brief (600-token cap), project chats, notes searchable in blaze.db | `ui/space/SpaceProjects.kt`, `agent/Projects.kt`, `agent/ProjectDocs.kt` |
| Profile = Settings root | Usage & plan; Profile, Scheduled Tasks, Accounts, Wallet; Security (+Bank mode), System Preferences (Appearance, Language, Voice, Permissions, Updates), Submit Feedback, About (licences) | `ui/space/SpaceProfile.kt`, `ui/SettingsSheet.kt`, `ui/SimplePages.kt`, `ui/Setup.kt` (BrainCard, PermissionsPage, UpdatesPage, AboutPage), `ui/space/SpaceLegal.kt` |
| Onboarding + one-screen setup | Logo → optional sign-in → one progress bar downloading Linux, tools, skills, backup brain, voice model, quick commands (Wi-Fi rules, resume) | `ui/space/SpaceOnboarding.kt`, `setup/SetupPlan.kt` (pure), `setup/SetupRunner.kt`, `setup/SetupGate.kt` |
| Spotlight | Search apps, Argus actions, settings, chats, web | `ui/space/SpaceSpotlight.kt`, `composer/SpotlightSearch.kt`, `SpotlightGroups.kt`, `SelfMap.kt`, `Fuzzy.kt` |
| Composer | Pill with +, mic→send, rotating on-device hints, slash commands, attachments | `ui/composer/ChatComposer.kt`, `ComposerHost.kt`, `ComposerState.kt`, `agent/ComposerHints.kt`, `agent/SlashCommands.kt`, `agent/Commands.kt` |
| Stage | Chat screen as a playground: one hero + docked widgets placed by `stage_*` tools | `stage/*`, `ui/StageHost.kt`, `ui/StageCards.kt` |
| Rich replies | charts, tables, compare, mini tools (safe `Expr`), choices, forms, galleries, link cards, doc cards, details, Mermaid diagrams (offline mermaid 11.17) | `ui/RichReplies.kt`, `RichKit.kt`, `RichInteractive.kt`, `RichMedia*.kt`, `RichBlocks.kt`, `Expr.kt`, `DiagramRender*.kt`, `agent/VisualRoute.kt` |
| Views | Learn, Design, Schedule, Gallery, Skills, Memory… chosen per request without a model call | `ui/Views.kt`, `agent/ViewRouter.kt`, `ui/LearnPage.kt`, `SchedulePage.kt`, `SkillLibraryPage.kt`, `MemoryPage.kt` + `MemGraph.kt` |
| Design studio | 3D (bundled three.js r170/r162), pages, art, slides; 10-version undo; private https origin | `agent/Designs.kt`, `ui/DesignView.kt`, `DesignServe.kt`, `DesignStudio.kt`, `PartsSketch.kt`, `assets/design/` |
| Design tokens (legacy + current) | `SpacePalette` (Obsidian #000, Argus Blue #0056FF, Electric Blue #1286FF, Steel #9AA0B3), one 44 dp control, 28 dp radius, rounded everything (`NoSquareTest`), Material Rounded icons, 90 % default size | `ui/space/SpaceLogic.kt`, `ui/Theme.kt`, `AppTheme.kt`, `ArgusBrand.kt`, `ArgusLook.kt`, `GlassKit.kt` (old glass), `Kit.kt`, `IconKit.kt`, `MenuIcons.kt`, `Motion.kt`, `Contrast.kt` |

### a.2 The agent
| Feature | Where |
|---|---|
| Agent loop (Session, turns, tool loop, repairs, guards) | `agent/Agent.kt` (`Agent.Session.runLoop`, `endTurn`, `runTool`, `runSpecialist`, `runNode`) |
| Rapid messages / message ids / owed-reply ledger | `agent/RapidMessages.kt` (`[m7]` ids, burst wait), `agent/ReplyLedger.kt`, `agent/BusyRoute.kt` |
| Background agents (never-blocking chat) | `agent/BgAgents.kt` (RAM cap 4/3/2/1, `bg_agents.json`, resume), `agent/Handoff.kt` (long turn → bg agent), `agent/TaskKeeper.kt`, `agent/TaskLine.kt`, `agent/ProgressGate.kt` (`post_update`) |
| Specialists (10 built-in + user/Argus-made) | `agent/Specialists.kt` (researcher, operator, writer, planner, coder, inbox, shopper, booker, tutor, designer), `agent/SpecialistMatch.kt`, `agent/Dispatch.kt` (`delegate`), `agent/Playbooks.kt` (validator), `assets/specialists/*.md` |
| Orchestra (blueprint DAG, parallel tools) | `orchestra/Blueprint.kt`, `Orchestrator.kt`, `Pipelines.kt` (apk / research / media / document / generic), `OrchestraRuns.kt`, `ToolScheduler.kt`, `KeyedLocks.kt` |
| Tool diet & routing | `agent/ToolRouter.kt` (groups, `load_tools`), `ToolTruth.kt`, `ToolPick.kt`, `ExecRoute.kt` (terminal-first), `SearchRoute.kt`, `InAppRoute.kt`, `FastActions.kt`, `LookupLane.kt`, `ChatLane.kt` |
| Honesty / guards | `ClaimGuard.kt`, `ClaimCheck.kt`, `ActionProof.kt`, `FakeCall.kt`, `ToolLeak.kt`, `ReplyClean.kt`, `LoopGuard.kt`, `RetryGuard.kt`, `EchoGuard.kt`, `EmptyReply.kt`, `VerifyPolicy.kt` |
| Approval & autonomy | `ConfirmGate.kt`, `CommandPolicy.kt`, `PermissionGate.kt`, `PlanGate.kt`, `Autonomy.kt` (always Autopilot; delete/pay/message still ask), `ui/ConfirmCard.kt` |
| BlazeQuery (fact check while typing) | `agent/query/*` (NeedDetect, QueryPlanner, SourcePolicy, Registries, Judge, EvidencePack, Research) |
| Skills | `SkillLibrary.kt` (seeded to `~/skills`), `SkillMatch.kt` (≤3 per turn), `SkillIndex.kt`, `SkillGuard.kt`, `SkillPacks.kt` (`save_workflow`), `CoreSkills.kt` (6 pinned), `Marketplace.kt` (GitHub `blaze-skill` topic, reviewed install), `Learning.kt` (Hermes-style post-turn review), `assets/skills/` (57) |
| Self-knowledge | `BlazeDocs.kt` (docs indexed into blaze.db at build), `SelfKnowledge.kt`, `SelfControl.kt`, `SelfSetup.kt` |
| Personas | `OnePersona.kt` (Halo merged into Argus in 0.51), `Duo.kt` / `DuoAll.kt` / `DuoText.kt` (two-companion code still present), `DuoWorkspace.kt` (multi-day shared folder + board), `HaloStudio.kt` (persona sliders), `Personas.kt`, `SharedPersona.kt`, `assets/persona-v1.json` |

### a.3 Models / brains
| Feature | Where |
|---|---|
| OpenAI-compatible client | `agent/LlmClient.kt`, `RequestBudget.kt` (≤60 KB request), `StreamGate.kt`, `Failover.kt`, `HedgeRace.kt`, `BrainRace.kt` |
| Argus Cloud (gateway, no user keys) | `agent/Cloud.kt` → `https://ai.blaze-app.workers.dev` (wire names `blaze-auto`, `X-Blaze-*`), `connect/Billing.kt`, `connect/Entitlements.kt`, `agent/Quota.kt` |
| Providers / free routing (legacy, hidden) | `Providers.kt` (pollinations, blazerouter, gemini, openai, anthropic, nvidia, moonshot, deepseek, groq, openrouter, mistral, cerebras, github, xai), `FreeRouter.kt`, `ProviderCatalog.kt`, `ModelMemory.kt`, `KeyRing.kt` |
| Tiers (no picker) | `composer/ModelChoice.kt` (always Auto; Insight/Oracle/Panoptes ≈ Spark/Flare/Inferno; `forRole`) |
| On-device | `FastLane.kt` (Cactus **Needle 3**, `libneedle.so`, weights in `filesDir/needle`), `OfflineBrain.kt` (Qwen2.5 0.5B via MediaPipe), `BackupBrain*.kt`, `agent/BlazeRouter.kt` + Go `blazerouter/` (OmniRoute port on 127.0.0.1) |

### a.4 Memory
| Feature | Where |
|---|---|
| One Mind (long tier) | `agent/Mind.kt`, `MindStore.kt`, `MindReview.kt` (idle review), `MindLogs.kt`, `Memory.kt`, `MemoryIntent.kt`, `MemoryGate.kt`, `MemoryMerge.kt`, `MemoryDelete.kt`; `store/BlazeDb.kt` (`filesDir/blaze.db`, schema **shared with Desktop/CLI**, FTS5) |
| Chat tier + cache tier | `memory/ChatTier.kt`, `CacheTier.kt`, `CacheStore.kt` (`filesDir/cache/cache.db`), `Chunker.kt`, `CacheToc.kt`, `MapReduce.kt`; `agent/LongChat.kt`, `AutoCompact.kt` |
| Pinned facts / "What Argus is holding" | `agent/PinnedFacts.kt`, `PinnedStore.kt` |
| Task memory | `agent/TaskMemory.kt`, `TaskMemoryStore.kt` (`task_records.json`) |
| Cloud sync (E2E, opt-in) | `connect/CloudSync.kt`, `SyncCrypto.kt`, `SyncEngine.kt`, `SyncMerge.kt`, `SyncAdapters.kt` |

### a.5 Phone, device, comms
| Feature | Where |
|---|---|
| Phone control (AccessibilityService, orient loop, pointer, settings jumps, OEM label matching) | `phone/BlazeAccessibilityService.kt`, `PhoneControl.kt`, `PhoneTools.kt`, `ScreenTree.kt`, `SettingsRoute.kt`, `SettingsJump.kt`, `LabelMatch.kt`, `Pointer.kt`, `agent/Orient.kt`, `phone/flash/*` (Flash loop) |
| Magic Hand (grab / OCR / teach / watch) | `phone/MagicHand*.kt` |
| Messaging, calls, SMS, USSD, contacts, calendar | `phone/Messaging.kt`, `CallControl.kt`, `CallCopilot.kt` (live captions), `Ussd.kt`, `PimEdit.kt`, `Skills.kt`, `SendCheck.kt` |
| Notification replies, group brain, scam/money radar | `notify/Conversations.kt`, `GroupBrain.kt`, `ScamShield.kt`, `MoneyRadar.kt`, `Sensitive.kt`, `StatusNote.kt` (one status notification) |
| Voice | `voice/Voice.kt`, `WakeWordService.kt` (Vosk, "Hey Argus"), `ReadAloud.kt`, `voice/myvoice/*` (sherpa-onnx PocketTTS clone), `voice/gemini/*` |
| Assistant entry points | `assist/BlazeAssist.kt` (VoiceInteractionService), `BlazeTile.kt`, `ui/Overlay.kt` (orb), `widget/BlazeWidget.kt` |
| Camera | `device/BlazeCamera.kt`, `CameraFlow.kt`, `CameraModes.kt`, `CameraZoom.kt`, `CameraSmart.kt`, `ui/CameraPro.kt`, `InAppCamera.kt` |
| Life logs / brainstorm | `life/*` (LifeLogService, Vosk transcription, chunks into journal) |
| Bank mode | `bank/*` (switches off phone control/Magic Hand while banking) |
| Device health | `device/Watchdog.kt`, `Thermal.kt`, `CrashLog.kt`, `StartLater.kt`, `StartWarm.kt`, `Perms.kt` |

### a.6 Terminal, browser, apps, media
| Feature | Where |
|---|---|
| Terminal (BusyBox phone shell; Alpine default, Ubuntu/Debian/Arch/Fedora/Manjaro via proot-distro rootfs; Termux PRoot 5.1.107.96; tabs; jobs) | `terminal/Terminal.kt`, `Linux.kt`, `Distros.kt`, `LinuxPm.kt`, `TermSessions.kt`, `BlazeTerminalActivity.kt` (Termux terminal-view), `JobKeeper.kt` (dataSync FGS), `Git.kt`, `DevTools.kt`, `CliAuth.kt`, `LinuxDoctor.kt`, `Fetch.kt`; native `jniLibs/*/libproot*.so`, `libbusybox.so`, `libtalloc.so`; `tools/proot`, `tools/busybox` |
| Browser (tabs, background agent tabs, secure input, saved logins, VPN via WARP/WireGuard, tracker block, location mask, saved pages, page ask) | `browser/*` (BrowserActivity, BrowserEngine, BrowserTools, SecureInput, BrowserLogins, WarpFree, BrowserVpn, TrackerBlock, LocationMask, PageAsk), Go `wgproxy/` |
| Argus Phone browser engine (single-page task surface) | `browser/phone/*` (`phone_*` tools, PhoneDriver, DomCache) |
| Site apps (PWA-like) | `siteapps/*` |
| App Store (curated APKs, VirusTotal check) | `appstore/*`, `store/catalog.json` (+ `build_cat.py`, `verify.py`, `STORE_CLEANROOM.md`) |
| Music / Video / Movies / Gallery | `mediaapps/*` (media3 MusicService, VideoActivity, Downloads, Lyrics, Blaze DJ, Clip Studio, BeatLab), `mediaapps/store/*` + `:storeapi` module (StoreSource), optional private `:youtube`, `movies/*` (QuickJS provider sandbox), `media/*` (Gallery) |
| Desktop link / local dashboard | `link/DesktopLink.kt` (QR pairing, pinned cert), `link/PcMessages.kt`, `dashboard/*` (127.0.0.1:8787 web chat) |
| Updates | `update/*` (in-app APK updates, VersionFeed) |

### a.7 Accounts, connectors, secrets
| Feature | Where |
|---|---|
| ARGUS account (blaze-auth: email/password, Google/GitHub PKCE loopback, MFA) | `connect/BlazeAccount.kt`, `Mfa.kt`, `AuthReturn.kt` → `https://blaze-auth.blaze-app.workers.dev` |
| On-phone OAuth connectors (Google incl. Classroom, GitHub device flow, Spotify, Notion, Slack) | `connect/Connectors.kt`, `Classroom.kt`, `GitHubApps.kt` |
| MCP client (Streamable HTTP + legacy SSE; per-tool Ask/Always/Off) | `connect/Mcp.kt`, `McpConfig.kt` |
| Key / skill sync via account | `connect/KeySync.kt`, `SkillSync.kt` (blaze-auth `/v1/skills`), `SkillMerge.kt` |
| Secrets | `agent/Secrets.kt` (Keystore AES-GCM `enc1:`), `SecretVault.kt` (`{{secret:…}}` handles, `secret_vault.json`), `SecretUse.kt`, `SecretGuards.kt`, `CredAsk.kt`, `ui/SecureFormCard.kt`, `ui/SecretCards.kt` |
| URL safety | `connect/UrlSafety.kt` (no cleartext secrets to public hosts) |

### a.8 Routines & proactivity
| Feature | Where |
|---|---|
| Scheduled prompts + shell jobs | `routine/Routines.kt`, `RoutineReceiver.kt`, `RoutineService` |
| Trigger routines (place arrive/leave, etc.) | `proactive/Autopilot.kt`, `Triggers.kt` |
| Proactive engine, dial, follow-ups, habits, Jarvis | `proactive/Proactive.kt`, `ProactiveDial.kt` (Off/Low/Normal/High), `FollowUps.kt`, `Habits.kt`, `Jarvis.kt` |

### a.9 Out-of-app components in the repo
| Folder | What | Note |
|---|---|---|
| `gateway/` | "Blaze Gateway 0.3.0" Cloudflare Worker source (TS, D1) | **Stale copy**: the live gateway (0.9.x, `ai.blaze-app.workers.dev`) lives in `blazeinchue/Blaze-agent`. Don't develop here. |
| `blazerouter/` | Go OmniRoute port, run on 127.0.0.1 inside Alpine | Optional local router |
| `wgproxy/` | Go user-space WireGuard → HTTP proxy for the browser VPN | Built before Gradle by CI |
| `store/` | App Store catalog + tooling | |
| `storeapi/` | Gradle JVM module: `StoreSource` interface | Only non-app module today |
| `tools/` | proot / busybox fetch & seccomp scripts, smoke gate, phone-bridge, CI helpers | |

---

## (b) Architecture

```
            ┌──────────────────────────── MainActivity (single Compose activity) ───────────────────────────┐
            │ ChatScreen: SpaceBackdrop + SpaceTopBar ─ Home (SpaceHome) │ Chat │ Projects (SpaceProjects)  │
            │              Spotlight layer · Settings (SpaceProfile → SettingsSheet pages) · Stage · cards   │
            └───────────────┬───────────────────────────────────────────────────────────────────────────────┘
                            │ user msg [mN]
                ┌───────────▼───────────┐   fast lane: FastLane (Needle 3, on-device) / FastActions / SmallTalk
                │ Agent.Session (lead)  │── lanes: ChatLane · LookupLane · BusyRoute (steer / parallel answer)
                │  runLoop → LlmClient  │── prompt: PromptParts + CoreSkills pins + Mind.block + RELEVANT NOTES
                │  ToolRouter groups    │           + ChatTier summary + CACHED DOCUMENTS + TaskMemory + Projects
                └──┬────────┬────────┬──┘
     spawn_agent / │ delegate│        │ orchestrate
                   ▼        ▼        ▼
              BgAgents (hidden Sessions, RAM cap)  ──►  OrchestraRuns → Orchestrator(Blueprint DAG) → Session.runNode
                   │  ToolScheduler + KeyedLocks (screen / browser / file / term locks across agents)
                   ▼
   tools: phone/* · browser/* · terminal/* · connect/Mcp · Connectors · Designs · media · notify · routine · memory · query
                   │
   LlmClient ──► Cloud (gateway ai.blaze-app.workers.dev, blaze-auto + blaze_task/tier) ─► fallbacks: OfflineBrain, Needle
```

### b.1 Agent loop
- **One lead agent per chat** (`Agent.Session`). A turn = user messages gathered by `RapidMessages` (burst wait 0.6–6 s,
  each message tagged `[mN]`), prompt assembled by `PromptParts`, model step via `LlmClient.complete` (request trimmed by
  `RequestBudget` to ≤60 KB: CF Free CPU limit), tool calls executed (parallel when independent: `orchestra/ToolScheduler`),
  repeat until a final reply; `ReplyLedger` checks every `[mN]` was answered (one follow-up call if not).
- **Never blocks**: anything longer than a couple of steps goes to `BgAgents` (`spawn_agent`, `delegate`, `orchestrate`);
  a turn near its deadline is handed off (`Handoff.kt`). Progress only via real tool results (`agent_progress`,
  `post_update` gated by `ProgressGate`). One task pill in chat.
- **Guards** wrap every step (claims, leaked tool syntax, loops, fake calls, empty replies, approvals). Autonomy is
  fixed at Autopilot; `ConfirmGate`/`CommandPolicy` still stop delete / pay / send.
- **Known structural limit:** `Agent.kt` is 5.8k lines and has already hit JVM "Method too large" (`runLoop`); new
  capability must live in new files/packages that the loop calls, not in `Agent.kt`.

### b.2 LlmClient / providers
- Production path: **Argus Cloud** only (`Cloud.kt`): one OpenAI-compatible gateway, account token `bz_…`, gateway picks
  model per `blaze_task` (chat, quick, code, vision, long, agent) and tier; failover in the gateway. No model picker
  (`ModelChoice` always Auto). Wire names stay `blaze-*` / `X-Blaze-*` (deployed contract).
- Legacy direct providers (`Providers.kt`, 14 OpenAI-compatible services incl. **xai**) + `FreeRouter`/`ProviderCatalog`
  still compile and are used by fallbacks; key UI is gone.
- On-device: Needle 3 fast lane (function calling for direct commands), Offline Brain (Qwen 0.5B), BlazeRouter.

### b.3 Memory / Mind
Three tiers (docs/MEMORY.md): **long** = `memories` + `mind_journal` in `blaze.db` (FTS5; kinds profile, preference,
person, project, decision, fact; side table `mind_mem` for subjects/related), pinned core + subject index in the prompt,
RELEVANT NOTES per turn; **chat** = rolling summary + token-capped tail, folded turns paged verbatim to cache;
**cache** = `cache.db` sections + TOCs for big pastes/files/tool output. Plus pinned facts (≤20), task records,
journal kinds `notif` / `life_log`. Optional E2E cloud sync. **Single user, single mind: no per-agent scope today.**

### b.4 Orchestra / specialists
- `Specialists.Spec(id, name, prompt, groups, skills, playbook, madeBy)`: a prompt + tool groups + skill packs +
  field playbook. Built-ins read `assets/specialists/<id>.md`; `create_specialist` adds new ones (validator).
  Specialists are **ephemeral workers** (a BgAgents run), not persistent personas: no own chat, memory, avatar, or home.
- `orchestra/`: generic DAG dataflow with owned paths, contracts, retries → fixer escalation, per-role tiers, snapshot/
  restore. Pipelines apk / research / media / document / generic.

### b.5 Terminal / proot
BusyBox phone shell always available; Linux = Termux PRoot with per-distro rootfs in `filesDir/linux/<id>` (Alpine
~3.5 MB default; Ubuntu & co. from proot-distro tarballs, xz via `org.tukaani:xz:1.10`); `~/phone` = Argus's home;
`run_shell(linux=true)`, `run_code`, git, background jobs (`JobKeeper` FGS). `ExecRoute` makes terminal the first choice
for command-shaped work. The XFCE/VNC desktop was **removed** (wave B-4) for RAM/size reasons.

### b.6 Connectors / MCP
On-phone OAuth (loopback 127.0.0.1:43117 PKCE, GitHub device flow, pasted tokens), tokens Keystore-sealed on the phone;
blaze-auth "one-click" providers; remote MCP client with `mcp__<server>__<tool>` naming and per-tool Ask/Always/Off;
notification-reply bridge for chat apps. **No argus-connect / Composio client yet.**

### b.7 UI shell & navigation
Single activity (`MainActivity`) + separate task activities for Browser, Phone browser, Terminal, Music, Video, Camera,
Site apps, Movies. Tabs via `ChatNav` (pure) + `SpaceTabs`; Back rule `BackRule.kt` (tests in `BackRuleTest`). Settings
pages addressed by string ids with `SpaceSettings.PARENT` back tree. Cards that need the user (forms, approvals, secure
input) always open Chat (`SpaceNav.showChat()`).

### b.8 `ui/space` design system
Pure tokens and rules in `SpaceLogic.kt` (palette, tabs, pill fit, greeting, card order, settings tree, test tags) with
composables in `SpaceTheme.kt` (`SpaceCard`, `SpaceHeadline` serif, `SpacePillButton`, `SpaceChip`,
`SpaceRoundButton`, backdrop with image cache). The unreleased "one look" round made `ArgusBrand`, `GlassTokens`,
`Light/DarkColors` and `blazeScheme` read `SpacePalette`; gutters 20, card padding 22, 44 dp controls, 28 dp radius,
one switch 46×28. Tests: `SpaceLogicTest`, `ContrastTest`, `ThemeEverywhereTest`, `SpaceWiringTest`, `SpaceShotsTest`.
Leftovers on the old look: browser `Sheet`, Terminal, Design, MeetingRoom, camera, phone overlays.

---

## (c) Merge map: A-team + Bot HQ → Argus-mobile

**Ground rule.** Argus-mobile is the host (Kotlin + Compose, package `com.blaze.assistant`). A-team is a WebView +
plain-JS + Java app (`ai.argus.agents`, own signing key) and Bot HQ is a Node/React server app around Hermes gateways on
an Orgo computer: neither is embedded. Their **capabilities and contracts are ported natively**; their **cloud pieces**
(argus-agents-api, argus-connect, gateway 0.9.x, Orgo, Composio, xAI, Hermes) are reached as services. A-team's
**look** replaces the space look's styling (slice 4 decides the tokens; this map only says where they land).

Legend — **Extend** = grow an existing Argus module · **New** = new package · **Unify** = two or three implementations
become one (winner named) · **Service** = stays server-side, app gets a client.

### c.1 Capability table

| # | Capability (source) | Lands in Argus | Kind | Unification / notes |
|---|---|---|---|---|
| 1 | **Team of bots as chats** (A-team bots; Bot HQ bots with name, look, role) | New `team/` (`Bot`, `TeamStore`, `BotThreads`) + `ui/team/` | New, built on `Specialists.Spec` | A **Bot = a persistent Specialist**: extend `Spec` with `soul`, `avatar`, `colour`, `memoryScope`, `workspace`, `voice`, `managedBy`, `modelPref?`. Each bot gets its own thread through the existing project-chat machinery (`ChatNav` already supports chats outside the main thread). The 10 built-in specialists become the seed bots of the life-domain ecosystem (slice 5). |
| 2 | **Manager bot** (A-team `manager.js`: routes, creates/updates bots, asks before delete, job status, user profile, routines, daily ≤3 suggestions) | The **Argus lead agent itself** (`Agent.Session` main thread) + `team/ManagerTools.kt` | Unify (Argus wins) | Don't add a second "Manager" persona beside Argus: Argus *is* the Manager (one mind, one main chat). Port the tools (`agents_list/create/update/delete/delegate/status`) as a tool group `team`; `delegate`/`spawn_agent` already route work. Manager's profile → Mind `profile`/`preference` memories (not a separate localStorage profile). Daily suggestions → `proactive/` with `ProactiveDial` gating. |
| 3 | **Jobs & approvals inline in each bot's thread**, waits-on deps, verified `done_job`, notifications (A-team `jobs.js`); per-bot task list (Bot HQ `create_task`) | New `team/Jobs.kt` (durable job records) executing on `BgAgents` + `orchestra/` | Unify | One job model: `Job{id, title, bot, deps, state queued/running/waiting/done/failed, due, priority}` persisted like `TaskMemoryStore`. Running a job = a `BgAgents` entry for that bot; deps = Blueprint edges (Orchestrator already waits on deps). Port A-team's **verified done** + **completeness gate** into `agent/VerifyPolicy`/`ToolVerify`. Fixes A-team's "one job at a time, app must be open" gap for free (WorkManager `TaskWorker`, RAM cap). |
| 4 | **Compose graph** (who reports to whom, live status, job nodes) | New `ui/team/TeamGraph.kt` | New | Data from `TeamStore` + `BgAgents` live state; reuse `MemGraph.kt` drawing discipline (≤20 fps, stops when idle/hidden). |
| 5 | **Ubuntu workspace per bot** (A-team `Workspace.java`, tmux window per bot, bg jobs supervisor `bgjobs.js`) | Extend `terminal/` (`Distros.kt`, `TermSessions.kt`) + new `terminal/BotWorkspace.kt` | Unify (Argus terminal wins) | **One rootfs, many homes**: per-bot home `/root/bots/<id>` + tmux window `argus:<bot>`; never one rootfs per bot (Ubuntu+tools ≈ 800 MB). Keep Alpine as the system distro (run_code, git, BlazeRouter pinned to `apk`); Ubuntu 24.04 becomes the optional **workspace distro**, downloaded by `SetupPlan` on Wi-Fi. Port two A-team fixes: tmux 3.3+ `server-access` ACL host config (`Pty.HOST_SCRIPT`/`ensureTmux`) and `hostLinks()` re-pointing `libtalloc.so.2` after app updates; and the detached-job supervisor (nohup/setsid, exit file) into `JobKeeper`. PTY: Argus's native Termux `terminal-view` wins over A-team's JNI + xterm.js. |
| 6 | **Computer you can watch / take over** (Bot HQ: Orgo screen per bot, noVNC viewer, watch vs control; A-team: cloud computer Orgo/Codespaces, lease + idle stop, live screenshot card) | New `computer/` (`ComputerProvider`, `Lease`, `CloudGuard`, `ComputerView`) + `ui/team/ComputerCard.kt` | New, Unify A-team + Bot HQ | Providers: `Orgo` (A-team lease rules incl. 5-min idle, 60-min cap, orphan stop, Java alarm + 15-min job backstop), `Codespaces`, `LocalBrowser` (Argus background tabs / Argus Phone engine rendered as frames), later `HermesBox` (a Bot HQ-style server). Viewer: screenshot polling first (A-team), VNC-over-WebSocket relay for take-over (Bot HQ `vnc.ts` pattern; needs a server relay so the VNC password never reaches the phone). **Do not** bring back the removed on-phone XFCE/VNC desktop. Keys in `SecretVault`, auth header added only for the key's host (A-team `CloudGuard.authFor`). |
| 7 | **Bots' own browser** (A-team `browser.js`, already *ported from Argus Phone browser*) | `browser/phone/*` (exists) | Unify (Argus wins) | Nothing to port back except per-bot browser profiles (`site-<bot>` WebView profile, like `SiteAppActivity`) so bots don't share cookies; `KeyedLocks` `browser` lock already serialises use. |
| 8 | **Connectors** (A-team → argus-connect `/v1/me/tools` + `/v1/me/execute`, per-user, keys server-side, personal MCP tokens; Bot HQ → Composio with approval cards, triggers) | Extend `connect/`: new `connect/ArgusConnect.kt` (catalog, toggles, execute), tools exposed like MCP tools | Unify + Service | **argus-connect is the one connector backend** (multi-tenant, keys never on the phone). Composio stays *behind* argus-connect (server side), never in the app. Keep on-phone `Connectors.kt` only for device-bound ones (Spotify playback, Classroom) and migrate the rest. Approval policy: reuse `Mcp.kt` Ask/Always/Off + `ConfirmGate`; add Bot HQ's **Always allow** per tool + "a new user message closes the card → skipped". Port A-team's connector-tool cap rules (connected apps first, ≤96) into `ToolRouter`. |
| 9 | **Vault** (A-team `SecretStore.java` + `vault.js`) | `agent/Secrets.kt` + `SecretVault.kt` | Unify (Argus wins) | Argus already has `{{secret:…}}` handles, Secure Form, scrubbing. Add key-to-host binding from A-team CloudGuard. |
| 10 | **Skills** (A-team My skills + Polish + read-only Argus originals via argus-connect `/v1/me/skills`; Bot HQ skills table) | `agent/SkillLibrary.kt`, `connect/SkillSync.kt` | Unify — **conflict** | Two sync backends today: blaze-auth `/v1/skills` (Argus) vs argus-connect `/v1/me/skills` (A-team). Pick argus-connect (per-user, Polish, originals) and migrate `SkillSync` once; bundled `assets/skills` stay the offline seed. Per-bot skill lists = `Spec.skills`. |
| 11 | **Memory / soul** (A-team hybrid: private per bot + team list + managers see reports; Bot HQ: `SOUL.md`, `MEMORY.md`, `USER.md` per bot, editable) | Extend `agent/Mind*` + `memory/`; new `team/Soul.kt` | Unify (Mind wins) | Add a **scope** (`user` / `team` / `bot:<id>`) via a new side table (like `mind_mem`) — **never alter the shared `memories` schema** (Desktop/CLI share blaze.db). Souls are editable markdown at `~/bots/<id>/SOUL.md` (+ `MEMORY.md` view generated from Mind rows), shown and edited in the bot's profile; prompt carries soul + bot-scoped pinned core. Memory sync: Mind's existing E2E cloud sync, not argus-agents-api. |
| 12 | **Routines / check-ins / proactivity** (A-team Manager routines once/daily/weekly/cron in local TZ, quiet hours, per-topic mutes; Bot HQ cron + **event routines** from Composio triggers, `set_checkins`, `stay_silent`) | Extend `routine/Routines.kt` + `proactive/` | Unify | Add `ownerBot` to a routine; event triggers come from argus-connect (server pushes → FCM-less: poll or Worker → notification); `stay_silent` = existing SKIP path in `ProgressGate`/`Proactive`. Quiet hours + daily cap into `ProactiveDial`. |
| 13 | **Bots talking to bots** (Bot HQ `send_to_bot`, group rooms ≤3 answering, subagents ≤4; A-team managers see reports) | New `team/Rooms.kt`, `team/BotMessaging.kt`; reuse `agent/Duo*.kt` | Extend | `Duo` already does parallel answers + one reaction round (`@all`); generalise from 2 personas to N bots with the 3-answerer cap. `send_to_bot` = `message_agent` addressed to a persistent bot thread. Guard: bot-to-bot loops (cap hops/turn, reuse `LoopGuard`). |
| 14 | **Texting-style UI with many bots** (Bot HQ Grok-Bot style sidebar, activity line, "Worked for 2m · 7 steps") | `ui/ChatScreen.kt` thread list + `ui/team/BotList.kt` | Extend | Argus already has work pills (`TaskPill`, `AgentPill`) = Bot HQ's activity chip. Add a bot list (A-team "Bots" home) as a Home card + Chat-tab switcher; bot avatars/colours in bubbles. **Do not copy Grok Bot artwork** (not MIT). |
| 15 | **Voice** (Bot HQ xAI realtime voice, handoff of real work to the bot's queue, TTS previews, dictation) | Extend `voice/` with `voice/xai/` | Extend | Same shape as `voice/gemini/`. Realtime session token minted server-side (gateway), never an xAI key on the phone; per-bot voice in `Spec.voice`. |
| 16 | **X search** (Bot HQ `x_search` via xAI) | `agent/query/` source + a `web` group tool | Extend | Route through the gateway (xAI provider exists in `Providers.kt`). |
| 17 | **Library** (Bot HQ files bots save; A-team `attach`/`save` + working set) | New `library/` view over `~/bots/<id>/files`, Downloads/ARGUS, designs, gallery | New (thin) | Reuse `SaveFile.kt`, doc cards, `ui/Gallery*`. Port A-team working set (last files written → "it" resolves) into `TaskMemory`. |
| 18 | **Hermes agent engine** (Bot HQ: every bot is a Hermes gateway, `/v1/responses`, MCP `/mcp/<bot>`) | Optional `agent/engines/HermesEngine.kt` | Service (optional) | Hermes is Python and server-side: not on the phone. The Argus loop stays the engine; a bot *may* be bound to a remote Hermes (on a cloud computer) through `/v1/responses`, with Argus exposing its tools over MCP (Argus has an MCP *client* only; serving MCP is new work, e.g. through argus-agents-api). Learning loop already follows Hermes' pattern (`Learning.kt`). |
| 19 | **Model per bot / subscriptions** (Bot HQ Engine panel; A-team Gemini with own key, `/v1/models/live`) | `composer/ModelChoice.kt`, `Cloud.kt` | Unify — **conflict** | Owner removed the model picker (0.52.3). Keep it removed in the UI; allow an optional per-bot *tier* (Insight/Oracle/Panoptes) and an advanced "own key" provider (Gemini) under Accounts. Adopt `/v1/models/live` only if the gateway contract needs it (Argus currently lets the gateway choose). |
| 20 | **On-device Needle commands** (A-team `cactus.wasm` in WebView + `needle-ft/` fine-tune data) | `agent/FastLane.kt` (native `libneedle.so`) | Unify (Argus wins) | Drop the wasm (≈37 MB of A-team `www/`); import `needle-ft` data to add team commands ("make a bot", "add a job for X", "what is Y doing") to the native fast lane. |
| 21 | **Team sync / external API** (A-team `argus-agents-api`: REST `/v1` + MCP `/mcp`, KV, per-account doc, last-write-wins) | New `team/TeamSync.kt` | Service | The bridge that lets ARGUS Desktop / any MCP client see the team, and the **migration path from the A-team APK** (it syncs up; Argus syncs down). Not E2E: keep memory and souls out of it (A-team already excludes memory). |
| 22 | **Built-in code review** (A-team `review.js`: static scan + strict reviewer) | `orchestra/Pipelines.kt` `verify`/`review` nodes + `assets/skills/code-review` | Extend | Becomes a review node after `build` in the apk pipeline and a gate for the Coder bot. |
| 23 | **Notch / Bots \| Chat switch**, edge glow, heads-up with bot avatar (A-team v2.16) | `ui/space/SpaceTopBar.kt`, `notify/` | Extend | Tabs become Home \| Chat \| Bots (or Projects folded into Bots/Home: slice 4). `AgentNotify` MessagingStyle Person per bot → `notify/Notifier.kt`. |
| 24 | **ARGUS account, plan, billing** (both) | `connect/BlazeAccount.kt`, `Billing.kt` | Same service | Same blaze-auth + gateway billing; nothing to merge. |
| 25 | **Deep research / multi-agent big jobs** (Bot HQ Hermes delegation; A-team jobs) | `orchestra/` | Argus wins | Already ahead (blueprint DAG, fact-check reviewers). |

### c.2 Overlaps resolved (one line each)
- **A-team proot vs Argus terminal** → Argus `terminal/` + Termux PRoot 5.1.107.96 wins; port tmux ACL + talloc relink fixes; one rootfs, per-bot homes/tmux windows.
- **A-team Manager vs Argus orchestra** → Manager = Argus lead + `team` tool group; jobs run on BgAgents/Orchestrator.
- **Specialists vs bots** → bots are persistent specialists (one `Spec` type), so `delegate` works on bots unchanged.
- **Connectors** → argus-connect (server) is the source of truth; Composio behind it; on-phone OAuth only for device-bound apps; MCP client kept for user-added servers.
- **Memory** → Mind is the store; A-team bot/team memory and Bot HQ soul/memory files become scoped views of it.
- **Skills** → SkillLibrary format (SKILL.md) everywhere; argus-connect as the sync backend.
- **Vault / secrets** → Argus SecretVault.
- **Routines** → `routine/` + `proactive/` with owner bot, quiet hours and event triggers.
- **Computer view** → new `computer/` serving both A-team cloud computer and Bot HQ per-bot screens.
- **Needle** → native runner; A-team wasm dropped.
- **Notifications / status** → Argus `StatusNote` + per-bot MessagingStyle; A-team edge glow optional.

### c.3 Conflicts that need an owner call
1. **Model choice per bot** (Bot HQ/A-team) vs **no model picker** (Argus 0.52.3 decision).
2. **Two skill-sync backends** (blaze-auth `/v1/skills` vs argus-connect `/v1/me/skills`).
3. **On-phone OAuth tokens** (Argus `Connectors.kt`) vs **server-held per-user credentials** (argus-connect) — privacy model change.
4. **One continuous mind** (Argus: no chat list since 0.24) vs **many bot chats** (A-team/Bot HQ). Recommendation: main thread stays the one mind; bot threads are scoped like project chats and searchable from the mind.
5. **Default distro** Alpine (3.5 MB) vs Ubuntu 24.04 (~800 MB with tools) for bots.
6. **Look**: A-team monochrome glass (`--bg #0a0a0a`, white solid, Lucide icons, no emoji, 52 px fields) vs Argus space view + Argus Blue + Material Rounded icons (slice 4).
7. **Halo/Duo**: owner merged Halo into Argus in 0.51 ("no separate persona for now"); multi-bot rooms reintroduce several personas — confirm that this reversal is intended.
8. **Two apps on the phone**: A-team (`ai.argus.agents`, key F7:8A:80…) cannot update into `com.blaze.assistant`; decide retire-and-migrate (via argus-agents-api sync) vs keep A-team as a companion.
9. **Hermes**: bring Hermes in only as an optional remote engine, or not at all.
10. **Repo `gateway/`** is a stale 0.3.0 copy; the live gateway is in Blaze-agent. Delete or mark it read-only to avoid edits in the wrong place.

---

## (d) Technical constraints, risks, recommended layout

### d.1 Constraints (hard)
| Area | Constraint |
|---|---|
| Process rules | Commit straight to `main`; every commit title ends with `[skip ci]`; **no build until the owner says build**. Every CI push is a 15–25 min round and workflows cancel-in-progress, so batch. Source of truth GitLab `blazeinchue/argus-mobile` (87806466); CI runs on the GitHub mirror (GitLab CI out of credits; `.gitlab-ci.yml` left alone). Never push/merge on GitHub. |
| Identity | Never change `applicationId` (`com.blaze.assistant`, owned by MR !26 `feat/argus-identity-signing`) or signing; never touch `keystore/` or the "Rotate signing key" CI step; release key lives outside the repo (v3 lineage). Keep the Kotlin package name too (prefs, components, tests pin it). |
| Build | Gradle 8.7, AGP with `compileSdk`/`targetSdk` 34, `minSdk` 26, JVM 17 (CI also JDK 21), Compose BOM 2024.09; `gradle.properties` memory settings must stay (7 GB runners OOM otherwise); Go 1.26 builds `wgproxy`/`blazerouter` before Gradle; `:youtube` optional private module. ABIs arm64-v8a + armeabi-v7a. |
| Data compatibility | `blaze.db` schema is shared with Desktop/CLI → only side tables; prefs must stay backward compatible; old setting keys keep defaults. |
| Deployed wire names | `blaze-auto`, `X-Blaze-*`, `blaze-auth`, `ai.blaze-app.workers.dev` stay until a two-name redeploy. |
| Licence | Argus is PolyForm Noncommercial 1.0.0. Bot HQ code is MIT (fine to port with notice in THIRD_PARTY_NOTICES) but its **Grok Bot artwork/icons are not** — don't copy. A-team has no LICENSE (same owner; add one before copying code). PRoot (GPL-2.0) ships as a separate executable (keep it that way); no GPL code linked into the app (NewPipe precedent). |
| Tests | No `@Ignore`, no skipping, no threshold changes (700 ms stall gate, 7 low-RAM smoke tests, PhonePerfTest). Source-scan tests (`NoSquareTest`, `BackButtonTest`, `ThemeEverywhereTest`, `IconOnlyTest`, `MoviesLegalTest`, skill hash / `store/catalog.json` sync) break on new files that ignore them. |

### d.2 Risks
| Risk | Detail | Mitigation |
|---|---|---|
| `Agent.kt` size | 5.8k lines; JVM "Method too large" already hit in `runLoop` | All team/computer/connector logic in new packages; the loop only calls a `TeamHooks` seam. Pure logic in pure files (the repo's pattern) so tests run without Android. |
| APK size | v0.34 APK was ~57 MB; today larger (MediaPipe genai, ML Kit, CameraX, media3, Vosk/JNA, termux, quickjs, jniLibs 5 MB, assets 6 MB) | Nothing large in the APK: Ubuntu rootfs, Needle/Qwen weights, voices, avatars download via `SetupPlan`. Never ship A-team's 37 MB `www/` (cactus.wasm/needle3.cact). Consider ABI splits / App Bundle later. |
| RAM / low-end phones | Owner's test phones are Galaxy A07/A12-class (4 GB); BgAgents cap 4/3/2/1; low-RAM smoke gate | Bots ≠ processes: a bot only costs RAM while it runs a job (one BgAgents slot). Team graph and computer view draw only when visible. Cap concurrent bot jobs with `AgentBudget`. |
| Background limits | Android 12+ phantom-process killer can kill proot children; Doze delays alarms (A-team cloud stop is inexact by design); FGS types required (dataSync ×4, mediaPlayback, microphone ×3 in the manifest); Android 15 dataSync 6 h cap once targetSdk ≥ 35 | Run workspace jobs under the existing `JobKeeper`/`TaskWorker` FGS; cloud-computer stop has a server-side backstop where the provider allows (Codespaces idle timeout) plus alarm + 15-min job; plan the targetSdk 35 bump separately (also needed for Play distribution). |
| proot | 64-bit ARM only for Ubuntu (A-team); seccomp/ENOSYS issues on older kernels (A12 4.19); tmux uid ACL; talloc path moves on update | Keep Argus's proven proot build + `LinuxDoctor`; port A-team's tmux/talloc fixes; Ubuntu workspace only on arm64, Alpine fallback elsewhere. |
| Storage | Ubuntu + tools ≈ 800 MB; per-bot files; cache 48 MB LRU | Wi-Fi-only, opt-in workspace distro; per-bot quotas; show sizes in Settings › Storage. |
| Keystore / secrets | Cloud computer keys, connector tokens, xAI voice sessions | Vault only; key-to-host binding; server-minted short-lived tokens for xAI realtime; nothing in logs, sync, backups (already excluded). |
| Security of autonomous bots | Bot HQ is unaudited; shell/browser actions not gated there; bots share one machine | Argus gates stay (CommandPolicy, ConfirmGate, Bank mode); per-bot homes and browser profiles; connector writes always pass the approval card; bot-to-bot hop limit. |
| Cost | Orgo $0.055/h with no server-side auto-stop | Off by default; A-team lease rules (idle 5 min, 60 min cap, orphan stop) ported as-is. |
| Migration of A-team users | Different package + key → no in-place update | Team (bots + jobs) via argus-agents-api sync; memory/profile export file; then retire the A-team APK. |
| CI churn | Big UI changes break screenshot/source-scan tests | Land per package, update the matching tests in the same commit, keep shots reviewed. |

### d.3 Recommended package layout for the unified app
Keep one `:app` module and the `com.blaze.assistant` package (identity + prefs + tests). New code goes in new packages;
pure rules in their own files with JVM tests, Android glue beside them.

```
com.blaze.assistant
├─ agent/            lead loop (frozen in size), guards, prompt, skills, specialists (Spec = bot base), lanes, query/
│  └─ engines/       NEW optional remote engines (HermesEngine over /v1/responses)
├─ team/             NEW  Bot (persistent Spec), TeamStore, Soul, BotThreads, ManagerTools (team tool group),
│                         Jobs (durable, deps), Rooms + BotMessaging (send_to_bot, ≤3 answerers), TeamSync (argus-agents-api)
├─ orchestra/        DAG, ToolScheduler, KeyedLocks (+ job deps, review node)
├─ computer/         NEW  ComputerProvider {Orgo, Codespaces, LocalBrowser, HermesBox}, Lease, CloudGuard, frames, take-over relay client
├─ terminal/         shell, distros (Alpine system + Ubuntu workspace), BotWorkspace (per-bot home + tmux window), JobKeeper
├─ connect/          account, billing, ArgusConnect (NEW: catalog, toggles, execute, triggers), Mcp client, device OAuth, sync
├─ memory/ + agent/Mind*   tiers + NEW scope side table (user / team / bot:<id>), soul/memory file views
├─ routine/ + proactive/   routines with owner bot, event triggers, check-ins, quiet hours, dial
├─ voice/            wake word, my voice, gemini, NEW xai/ realtime
├─ library/          NEW thin view over bot files, designs, downloads, gallery
├─ phone/ browser/ media/ mediaapps/ movies/ notify/ life/ device/ stage/ setup/ update/ appstore/ siteapps/ link/ bank/   unchanged
└─ ui/
   ├─ space/         design system (re-skinned to the A-team look per slice 4; tokens stay pure in SpaceLogic)
   ├─ team/          NEW BotList, BotChat header/profile (soul editor), TeamGraph, JobBoard, ComputerCard/Viewer, RoomChat
   └─ (existing screens move onto ui/space tokens)
```

Later, optional (only after the owner allows builds): extract pure packages (`team` rules, `orchestra`, `memory`
chunking) into JVM Gradle modules like `:storeapi` for faster tests; not part of the first phases.

### d.4 Suggested order (input for slice 6)
1. `team/` data model + Bot = persistent Spec + bot threads (no UI change beyond a bot list) → 2. Jobs on BgAgents +
Manager tools → 3. memory scopes + souls → 4. argus-connect client + approvals → 5. per-bot workspace in `terminal/` →
6. routines/check-ins per bot → 7. rooms / bot-to-bot → 8. `computer/` (Orgo/Codespaces, view, take-over) → 9. voice (xAI),
x_search, Library → 10. A-team look across `ui/space` + `ui/team` → 11. A-team user migration and retirement.

## Sources read
`argus-mobile`: README.md, STATUS.md (top rounds), CHANGELOG.md (unreleased → 0.51.0), HANDOFF.md, HANDOFF-argus-hark-design.md,
P.H.A.md, BUILDING.md, settings.gradle.kts, app/build.gradle.kts, AndroidManifest.xml, docs/AGENTS.md, MEMORY.md,
TERMINAL-FIRST.md, HANDOFF-0.52.md, removed.md, package tree and file headers under `app/src/main/java/com/blaze/assistant/**`,
gateway/README.md, blazerouter/main.go, wgproxy/main.go, .gitlab-ci.yml.
`a-team`: README.md, HANDOFF.md, HANDOFF-v2.16.md, HANDOFF-connectors.md (head), api/README.md, `app/www/*.js` headers, app.css tokens.
`bot-hq`: README.md, docs/ARCHITECTURE.md, server/src file list, `agent/tools.ts` tool names, `engine.ts` (SOUL.md).
