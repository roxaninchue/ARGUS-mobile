# P5-AG-01 · Domain catalog — log

> Branch `overhaul/p5-ag-01` (cut from `overhaul/p5`). Ticket row: `06-team-plan.md` §6.6 P5-AG-01. Covers NEW-02.
> Built by Hark for Argus/Finn, 2026-11-11. Owner not available: decisions below were taken from 05/04/06 and are recorded here.

## What was built
| File | What |
|---|---|
| `app/src/main/assets/domains/index.json` | format 1, the 14 ids in roster order, `seeded` (tempo, inbox), the 10 Life areas (id, label, hue, glyph, domains, related, lead) |
| `app/src/main/assets/domains/<id>/domain.json` ×14 | the template (schema below), sourced from 05 §3.2 / §3.3 and 06 R5 |
| `app/src/main/assets/domains/<id>/SOUL.md` ×14 | seed soul, parses with `team/Soul.kt` (P3-AG-03): front matter id/name/label/version 1/`locked_sections: [Boundaries]`; Who / How / What you own / Standing rules (empty) / Boundaries (the 3 shared `Souls.BOUNDARIES_TEXT` lines + the domain's own from 05 §3.2 "Approvals") |
| `app/src/main/assets/domains/<id>/PLAYBOOK.md` ×14 | field playbook with the six `Playbooks.SECTIONS`; passes `Playbooks.validateDomain` against the other 13 domain playbooks **and** the 10 specialist playbooks (no copies) |
| `app/src/main/assets/domains/<id>/skills/<slug>.md` ×14 | one domain skill each, SKILL.md front matter (name, description, tags, version) |
| `app/src/main/java/com/blaze/assistant/team/Domains.kt` | pure catalog: areas, template model, parse/render, validator, loader, `toBot`, `installSoul`, `profile`, `hintHits`, create-domain-from-template; `UserDomains` (filesDir/domains) |
| `app/src/main/java/com/blaze/assistant/team/DomainAssets.kt` | Android glue: `DomainAssets.install(context)` sets `Domains.loader` to `assets/domains/` and installs `UserDomains(filesDir)` |
| `app/src/main/java/com/blaze/assistant/agent/Playbooks.kt` | **extended** (existing behaviour unchanged): `domainField`, `validateDomain` (= `validate` + "# <Name> playbook" title + an approvals line), `forDomain(id)` (cached), `resetDomains()` |
| `app/src/test/java/com/blaze/assistant/team/DomainTemplateTest.kt` | 13 tests (below) |

## The 14 domains → 10 Life areas (06 R5)
| Area (hue, glyph) | Domains (first = default lead) |
|---|---|
| Health `#22C55E` heart-pulse | Pulse |
| Money `#F59E0B` wallet | Ledger, Scout |
| Work `#6366F1` briefcase | Career, Inbox, Tempo, Forge |
| Home `#06B6D4` house | Hearth, Pantry, Clerk |
| People `#EC4899` users | Circle |
| Learning `#8B5CF6` graduation-cap | Tutor |
| Travel `#3B82F6` plane | Atlas |
| Create `#EF4444` palette | Studio |
| Mind `#CBD5E1` notebook-pen | — Manager-owned (related: Pulse via `health:mind`) |
| Phone `#9B9BA3` smartphone | — Manager-owned (Operator worker does the taps) |

Memory scopes owned: tempo `time`, inbox `comms`, pulse `health` **private** (+ optional `health:mind`), ledger `money` **private**, career `work`,
forge `work:argus`, hearth `home`, pantry `food`, atlas `travel` (reads `money:budget-caps`), circle `social`, tutor `learning`, clerk `admin` **private**,
scout `shopping` (reads `money:budget-caps`), studio `creative`.

## `domain.json` schema (format 1)
```jsonc
{
  "format": 1,                      // Domains.FORMAT
  "id": "ledger",                   // = folder name = bot id (persona slug, lower-case, not "manager"/"worker…")
  "label": "Money",                 // fixed life-domain label (D2: shown under the name)
  "defaultName": "Ledger",          // renamable persona name; unique in the catalog; never a reserved Manager name
  "area": "money",                  // one of the 10 Life area ids; built-ins must be listed in that area (Domains.AREAS)
  "about": "Budgets, bills, …",     // one line: what it does (≥20 chars); also the soul's "What you own" for regenerate/reset
  "avatar": {"shape": "orb", "face": 0-5, "color": "#RRGGBB", "glyph": "<lucide name>"},
  "seeded": false,                  // true only for tempo + inbox (= Bots.SEED_DOMAINS)
  "proposeFor": ["founder"],        // optional: setup pre-proposes it (Forge for Finn, 05 §3.2 note)
  "basedOn": "hearth",              // user domains only: the template it was created from
  "routingHints": ["budget", …],    // lower-case words/phrases; ≥8 for built-ins (≥3 for user domains); UNIQUE across the catalog
  "toolGroups": ["bank", …],        // ⊆ Domains.TOOL_GROUPS (Specialists.groupNames + 05 §6.1 groups); must include "memory"
  "suggestedApps": ["sheets"],      // connector suggestions, never auto-connected
  "workers": ["researcher"],        // ⊆ Domains.WORKERS (10 built-in specialists + reviewer)
  "skills": ["bills-calendar"],     // files skills/<slug>.md in this folder (Bot.skills gets "domain:<id>/<slug>")
  "sharedSkills": ["data-analysis"],// existing assets/skills/<slug>/SKILL.md packs
  "memory": {"owns": "money", "private": true, "reads": ["user:core", …], "optional": ["health:mind"]},
                                    // owns = a MemScopes domain scope, unique; private MUST equal MemScopes.isPrivateDomain(owns);
                                    // reads must include user:core and never another domain's private scope
  "permissions": {"autonomy": "ASSIST|ACT|AUTOPILOT", "actions": {"SPEND": "ASK", …}, "spendCapMonthly": 0, "currency": "ZMW",
                  "sendScope": "NONE|DRAFT_ONLY|KNOWN_CONTACTS|ANY"},
                                    // keys = team.ActionClass, values = team.Rule; SPEND/SEND/DELETE/CREDENTIAL never ALLOW;
                                    // cap starts at 0; private domains only NONE/DRAFT_ONLY
  "approvals": ["Any payment, …"],  // human lines: what always shows a card (05 §3.2); private domains must list sharing
  "routines": [{"name": "Bills due", "kind": "CRON|EVENT|CHECKIN|WATCH", "schedule": "0 8 * * *", "prompt": "…", "silentIfNothing": true}],
                                    // CRON/WATCH = 5-field cron, EVENT = dotted signal (+ ":arg", e.g. "place.leave:home",
                                    // "calendar.before:30m"), CHECKIN = "HH:MM". All OFF until the user accepts (P6-PR-01 owns scheduling)
  "signals": ["notification.bank"], // SignalBus subscriptions for P6-PR-03 (dotted names)
  "suggestions": {"maxPerWeek": 2, "topics": ["bill due", …]}   // 0..7, for the P6 arbiter
}
```

## `Domains.kt` API (for P5-TM-04, P5-SH-01, P5-SH-03, P6-PR-03)
- Constants: `IDS` (14, roster order), `SEEDED`, `PRIVATE` (pulse, ledger, clerk), `TOOL_GROUPS`, `WORKERS`, `ROUTINE_KINDS`, `FORMAT`.
- Areas: `AREAS: List<Area(id, label, hue, glyph, domains, related)>` with `managerOwned` / `lead`; `area(id)`, `areaOf(domainId)`.
- Model: `Template(id, label, defaultName, area, about, avatar: Look, routingHints, toolGroups, suggestedApps, workers, skills, sharedSkills,
  memory: Memory, permissions: Perms, approvals, routines: List<RoutineT>, signals, suggestions, seeded, proposeFor, format, builtIn, basedOn)`
  + `private`, `dataScopes`, `display`.
- JSON: `parse(json, builtIn): Parsed.Ok|Bad`, `toJson(t)`, `render(t)` (round-trips).
- Validation: `validate(t, others): Check` (data rules incl. private scopes and hint uniqueness), `validateFiles(t, soul, playbook, skill, otherPlaybooks)`
  (SOUL.md via `Souls.parse`, PLAYBOOK.md via `Playbooks.validateDomain`, skills front matter), `fieldOf(t)`, `isCron`, `frontMatter`.
- Loading: `loader` (assets/domains/<path>), `userLoader` + `userIds` (filesDir/domains), `catalog()` (built-ins in index order, then user domains;
  cached), `get(id)`, `byArea(areaId)`, `forScope(scope)`, `indexIds()`, `soul(t)`, `playbook(t)`, `skill(t, slug)`, `reset()`.
- Roster: `toBot(t, now, id, name, enabled)` → `Bot` (kind DOMAIN, domain = memory.owns, reportsTo manager, Permissions from the template over
  `DEFAULT_RULES`, dataScopes = user:core + team + owns + reads); `profile(t, bot, owner)` → `Souls.Profile`; `installSoul(port, t, bot, now)` writes the
  template SOUL.md as version 1 when the bot has none (renamed bots get their name); `hintHits(text, hints)` (word-boundary keyword count).
- Create from template: `fromTemplate(NewDomain(name, label, about, area?, hints, basedOn?, private), base, existing, takenBotIds): Created.Ok(template, soul,
  playbook = null) | Refused(why)`; persist with `UserDomains(filesDir).save(t, soul, playbook)`, `install()`, `delete(id)` (never a built-in).
- Playbooks: `Playbooks.validateDomain(md, field, others)`, `Playbooks.domainField(...)`, `Playbooks.forDomain(id)`, `Playbooks.resetDomains()`.

## Decisions (owner unavailable)
1. **Ids are persona slugs** (`ledger`, not 05 §3.3's example `money`): the roster already seeds `tempo`/`inbox` by persona id (P1-TM-01), and D2
   keeps persona names. The life label stays in `label`; the memory scope (`money`) is `memory.owns` and becomes `Bot.domain`, which is what
   MemScopes uses for own-domain access.
2. **Tempo and Inbox match the seeded records exactly** (avatar, groups, hints from `Bots.tempo/inbox`): `Bots.sameIdentity(toBot(catalog), Bots.seed())`
   is tested, so the catalog and the roster never disagree. `Bots.kt` was not touched (outside this ticket's files); P5-SH-01 can switch
   `Bots.seed` to `Domains.toBot` later with no data change.
3. **Routing hints are unique across the catalog** (validator rule), so the keyword stage of Routing v2 has one winner; a 14-phrase sanity set picks the
   right owner with no tie (test). Embedding scoring is P5-TM-04's.
4. **Tool groups**: 05 §3.2's group words were mapped to groups that exist: calendar → `connectors` (gcal tools sit there in `Specialists.group`),
   contacts → `comms`, pdf → `files`, Linux → `terminal`/`ubuntu`. 05 §6.1 groups that aren't enforced yet (`bank`, `health`, `files`, `dev`, `cloud`,
   `media`, `ubuntu`) are allowed and stay inert until the P4 ToolRegistry maps them.
5. **Private scopes** come from `MemScopes.PRIVATE_DOMAINS` (health, money, admin, health:mind): the validator refuses a template whose `private` flag
   disagrees, or which reads another domain's private scope. Atlas/Scout read `money:budget-caps` (a separate, non-private scope, per 05 §3.2).
   A user-made domain can't be private yet (MemScopes has a fixed private set) — `NewDomain.private` is accepted but not honoured; logged for P3.
6. **Templates never pre-allow** SPEND/SEND/DELETE/CREDENTIAL and start with spendCap 0; Ledger SEND = NEVER, Forge CREDENTIAL = NEVER (05 §3.2, §7).
7. **Create-from-template** copies the base's groups, workers, apps, permissions, approvals, signals and its How/Boundaries soul text (renamed); the scope is
   the label slug, or `x-<id>` when that would take an existing or private scope; hints another domain owns are dropped. The playbook is **not** copied
   (it would fail the copy check and isn't about the new field): the caller generates one with `Playbooks.generateMessages` and gates it with
   `validateDomain(md, fieldOf(t))` before `UserDomains.save`.
8. Routines are data only, all off by default; scheduling is P6-PR-01. Signals are names for P6-PR-03's SignalBus.
9. Copy uses "the user" (no owner name in assets); the Manager/SoulStore can personalise.

## Seams
- `team/Models.kt` (P0-TM-01) is still not on any branch: compiled against a scratch copy of 05 §9.1 + a `DEFAULT_RULES` map (outside the repo, never
  committed), as P1/P3 did. Re-run DomainTemplateTest once P0 lands.
- `DomainAssets.install(context)` has no caller yet (Agent.kt is off limits): TeamHooks/TeamAndroid (P0-AG-01) or the first Setup screen (P5-SH-01) calls it.
  Until then `Domains.catalog()` is empty on the phone and `Playbooks.forDomain` returns "".
- Prompt assembly (locked core → SOUL → domain playbook → skills → memory → Now) is the engine's (P0-AG-01 / P3-AG-03 PromptParts); it can use
  `Playbooks.inject(name, Playbooks.forDomain(id))` for the playbook block.
- P5-TM-04: `Domains.catalog()` + `routingHints` + `hintHits` (keyword stage). P5-SH-01: `IDS`, `proposeFor`, `AREAS`, `toBot`, `installSoul`.
  P5-SH-03: `AREAS` (hue/glyph/lead), `byArea`, `areaOf`. P6-PR-03: `signals`, `suggestions`.

## Tests run (sandbox: kotlinc 2.0.21, JDK 17, junit 4.13.2, org.json 20240303)
| Class | Tests | Result |
|---|---|---|
| `team.DomainTemplateTest` | 13 | ✅ all pass |
| `agent.PlaybooksTest` (existing, Playbooks.kt changed) | 7 | ✅ all pass |
| `team.SoulParseTest` (existing, souls parse with it) | 13 | ✅ all pass |

Total 33/33 green. `DomainAssets.kt` compiled against `android.jar` stubs. Compile set: Models (scratch) + scratch stubs for `Settings`, `Web`, `Designs`,
`phone.Skills`, `terminal.DevTools` (Specialists' closure) + BrandName, Specialists, Playbooks, Bot, Soul, Domains, MemScope.
Note: `kt-test.sh` in the brief leaves `kotlin-stdlib.jar` off the runtime classpath; a local copy adds `kotlinc/lib/kotlin-stdlib.jar`.

## Left for a device build
- Run DomainTemplateTest under Gradle (`testDebugUnitTest`) against the real `Models.kt`; check AAPT packs `assets/domains/**` (no `aaptOptions` ignore
  pattern matches `SOUL.md`/`PLAYBOOK.md`).
- Wire `DomainAssets.install` at team start; seed souls with `installSoul` when setup switches a domain on.
