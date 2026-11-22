# Handoff 2026-11-22: lean harness + cloud tool loop

## Live / shipped
- Gateway **0.9.38 LIVE** (64a70d84, deployed 22 Nov 10:57 CAT on Finn's go). Rollback: `npx wrangler versions deploy dfe137f9-2009-443c-9137-743cdc8d36ce@100%` (0.9.37).
- Gateway **0.9.39 uploaded, NOT deployed**: a80009fe (Blaze-agent main 4ae19071). Rollback target once deployed: 64a70d84.
- App **0.55.0 (209)** on main 2c284840: CI build green (GitHub mirror run 45003700014, artifact argus-debug-signed-2c284840…). app-template job failed on a flaky Maven fetch (template untouched; passed on 0.54.6).

## 0.55.1 (210) — WIP on main, [skip ci], NOT built
Pushed: 81920aa7 (CloudLoop, LoopWire, PhoneBatch, version 0.55.1/210) + this WIP commit:
- Lean prompt: VOICE / GREETINGS (no bare "Hi") / TIME / WHO SAID WHAT rules; `LeanTurn.nowLine` CURRENT LOCAL TIME + locale after the cached prefix (Finn's 11:05 "study 9-12" bug).
- ToolVerify: set_alarm no longer says "couldn't confirm" when the Clock app accepted the intent and an earlier alarm hides it (`nextAlarmAt`, `nextAt`, `REPORTED`).
- Tests touched: LeanTurnTest, ToolVerifyTest, new PhoneBatchTest, LoopWireTest — NOT all re-run after the last edits.

## Done since (11:10–11:45 CAT)
- Tests re-run (kotlinc 2.0.20 + stubs): 105 OK across PhoneBatch, LoopWire, ToolVerify, LeanTurn, PhoneReadout, DirectLane, TurnTiming, SpaceLogic, SpaceWiring, InterestsLine. Fixed: LeanTurnTest `.map(::java.io.File)` (didn't compile); ToolVerifyTest now expects FAILED when the Clock app refuses an alarm.
- Phrase readouts were already gone in 81920aa7 (Settings > Developer > Speed stats, What happened sheet, speed_stats tool); PhoneReadoutTest confirms.
- "You chose to study": traced to Home setup's interest chips saved to memory as "Interested in Study: …" (kind preference), read back as a choice. Now "Home topics from setup: … (not a plan …)" (InterestsLineTest) plus the WHO SAID WHAT rule.
- New TIME rule: when the user points out the time, own the slip and re-plan from now (Finn's "It's 11:07" got a bare echo).
- Docs: CHANGELOG, STATUS, overhaul.md, about.md 0.55.1; rename baseline updated; do-not-port 0; evals 34/34.

## Left to do
1. Build commit (no [skip ci]) and watch GitHub mirror CI.
2. Deploy gateway 0.9.39 only on Finn's go.
3. Phone check (see STATUS 0.55.1).

## Design rules (Finn)
- No trigger words / text routing: the model decides; `final` comes from the tool name (gateway list).
- No local model (no user RAM). No keep-warm ping.
- Cloud tools (web_search, fetch_page, find_tools, load_skill, speed_stats) run in the gateway loop; phone tools go as one HMAC-signed ordered batch; phone executor stops on first failure; all-final success ends the turn locally; otherwise /v1/turn/resume with turnId + results (TTL ~5 min, full-resend fallback).
- Agent.kt runLoop at the 64 KB method limit: add nothing inside it.
- Push via work/glpush.py (git over HTTPS fails in the sandbox).
