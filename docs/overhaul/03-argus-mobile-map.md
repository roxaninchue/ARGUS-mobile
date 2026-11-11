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
