# ARGUS Overhaul — Argus-mobile × A-team × Bot HQ

> Living handoff. If you're picking this up mid-way: read **Status** and **Handoff log** first, then the slice docs in `docs/overhaul/`.
> Rules: commit straight to `main`, every commit title ends with `[skip ci]`, no build until the owner says build.

## Goal
One unified ARGUS app (this repo, `argus-mobile`, Kotlin + Compose) that contains:
1. **Everything in Argus-mobile** today (Blaze Android: chat, voice, orb, phone control, terminal, browser, media, memory, life logs, routines…).
2. **Everything in A-team** (`gitlab.com/blazeinchue/a-team`: team of bots as chats, Manager bot, jobs/approvals inline, Compose graph, Ubuntu workspace per bot, cloud computer, connectors, vault, skills, Needle on-device commands, `argus-agents-api`).
3. **Everything in Bot HQ** (`github.com/nickvasilescu/bot-hq`, MIT, from Nick Vasilescu's video "I Stole the Best Part of Every AI Agent… With One Prompt"): Grok-Bot-style texting UI with many bots + voice + X search, Muse-style social connections + editable soul/memory files, Dots-style per-agent computer you can watch/take over, Hermes agent engine, Composio apps with approval cards, routines/tasks/Library, bots talking to bots.

**Design:** A-team's design language is the base. Every feature must be reachable neatly, creatively, and in few taps.
**Vision:** an ecosystem of specialist agents — one per task and per part of the person's life — orchestrated by a Manager.

## Specialist slices (each writes its own doc, then this file is merged)
| # | Slice | Doc | Status |
|---|---|---|---|
| 1 | Bot HQ full feature inventory + port notes | `docs/overhaul/01-bothq-inventory.md` | in progress |
| 2 | A-team full feature inventory + port notes | `docs/overhaul/02-ateam-inventory.md` | in progress |
| 3 | Argus-mobile inventory, architecture & merge map | `docs/overhaul/03-argus-mobile-map.md` | in progress |
| 4 | Design system + information architecture (A-team look, every feature placed) | `docs/overhaul/04-design-ia.md` | in progress |
| 5 | Agent ecosystem architecture (life-domain specialists, Manager, soul/memory, bot-to-bot, computers, models) | `docs/overhaul/05-agent-ecosystem.md` | in progress |
| 6 | Integrated team plan: phases, workstreams, owners, milestones, risks, tests | `docs/overhaul/06-team-plan.md` | waits on 1–5 |

## Status
- 2026-11-11: Overhaul kicked off. Sources pulled: argus-mobile@main, a-team@main, bot-hq@HEAD. Specialists 1–5 dispatched.

## Handoff log
Append one line per session: date, who, what changed, what's next.
- 2026-11-11 · Hark · Created this file and the slice table. Next: slice docs land, then slice 6 merges them into the plan below.

## Unified plan
_(filled by slice 6)_
