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

## Left to do (0.55.1)
1. Re-run app tests (kotlinc + stubs in work/tc): LeanTurnTest, ToolVerifyTest, PhoneBatchTest, LoopWireTest, DirectLaneTest, PhoneReadoutTest, ModelFirstTest.
2. Remove the phrase-matched readouts ("speed stats", "tool stats", "what's running") from FastLane/PhoneReadout (Finn: NO trigger words anywhere). Keep TurnTiming; show it on a Settings > Developer "Speed stats" screen + What happened sheet; `speed_stats` catalog tool (gateway catalog already has it, e5614c0).
3. Trace "You chose to study" (a chip/memory injected as the user's words?).
4. CHANGELOG / STATUS / overhaul.md / about.md 0.55.1 lines; SpaceWiringTest pin theVersionIs0551; check-no-blaze --update-baseline; check-do-not-port; `node --test evals/test/*.test.mjs` (34/34).
5. Final build commit without [skip ci]; watch GitHub mirror CI.
6. Deploy gateway 0.9.39 only on Finn's go, after the app is ready.

## Design rules (Finn)
- No trigger words / text routing: the model decides; `final` comes from the tool name (gateway list).
- No local model (no user RAM). No keep-warm ping.
- Cloud tools (web_search, fetch_page, find_tools, load_skill, speed_stats) run in the gateway loop; phone tools go as one HMAC-signed ordered batch; phone executor stops on first failure; all-final success ends the turn locally; otherwise /v1/turn/resume with turnId + results (TTL ~5 min, full-resend fallback).
- Agent.kt runLoop at the 64 KB method limit: add nothing inside it.
- Push via work/glpush.py (git over HTTPS fails in the sandbox).
