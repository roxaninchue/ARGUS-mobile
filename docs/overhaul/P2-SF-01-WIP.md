# P2-SF-01 — continue notes (WIP, 2026-11-13 14:17 CAT)

**State:** the code commit `ed3c75e7` ("P2-SF-01: ActionClass on every tool … [skip ci]") had already landed on `main`
(on top of 589cba90) before the owner asked for a WIP branch. This branch `overhaul/p2-sf-01` starts at `ed3c75e7` and
adds only this file. If the code should not be on main yet, revert `ed3c75e7` on main (owner's call; not done).
The docs commit (overhaul.md Status / Handoff / Deviations, 06-team-plan ✓) is **not** done, and P2-SF-01 is not ✓.

## Done (in ed3c75e7)
- `policy/ActionClass.kt`: `ToolRegistry` — every tool (~260) declared with one ActionClass (enum stays `team.ActionClass`),
  per-`action` classes for multi-action tools, `selfCard` for ask_user / request_secret / browser_secure_input,
  MCP tools classed by readOnly/destructive hints, undeclared = WRITE_REMOTE.
- `policy/Policy.kt`: `Policy.decide(call, ctx)` in 05 §7.1 order: NEVER / MCP Off / send scope NONE → refuse;
  Plan + Assist refuse non-reads; SPEND/DELETE/CREDENTIAL/COMPUTE_PAID always card; SEND draft card (standing only:
  user-started, untainted, scope ≥ KNOWN_CONTACTS); A-team RISKY on unconfirmed proactive work; ConfirmGate taint
  (≥ WRITE_REMOTE asks); CommandPolicy RISKY asks; MCP Ask/Always; standing policy / tool grant / command allowance;
  class rule; Autopilot; else card. Bot chains use the lowest permission (`effective`), standing only for direct requests.
- `policy/Approvals.kt`: in-call approvals, 25-min expiry, new user message → skipped, `ApprovalStore.expireLeftovers`
  on start (restart → expired), `PolicyStore` on team.db `policies`.
- `policy/PolicyGate.kt` (pure gate, runs approved call under `ConfirmGate.PolicyOk`), `PolicyCalls.kt` (pure glue),
  `PolicyRuntime.kt` (Android glue). Wiring: Agent.kt runTool line wrapped + 1 line in `confirm` (seam 9/20);
  TeamHooks `Binding.origin` + `onUserMessage`; JobsRuntime sets origin + `PolicyRuntime.init`; LocalEngine marks bot-to-bot turns;
  ConfirmGate `taintAsks/alwaysCard/PolicyOk`; CommandPolicy `actionClass`; ToolRouter `actionClass/undeclared`.
- Enforced only for non-Manager bots and work the user didn't start (routine/suggestion/bot/app); the user's own ARGUS
  chats are untouched (fast path) until P2-SF-02 cards. Interim card = today's `Agent.confirm` (2-min timeout = denied).

## Tests
- Local JVM (kotlinc 2.0.21, JDK 17, sqlite-jdbc): **184/184 pass** — PolicyTest 10 (85-row table), ApprovalsTest 12,
  ToolRegistryTest 6, PolicyCallsTest 4, ConfirmGateTest 6 + CommandPolicyTest 11 (unchanged), regression team/* 13 classes
  + LocalEngineTest + TeamHooksTest = 135. `check-do-not-port` 0 hits; evals 34/34.
- Full-source compile (weak check, 51k errors from missing libs): 0 errors in policy/*, TeamHooks, ToolRouter, ConfirmGate,
  CommandPolicy, LocalEngine, JobsRuntime; Agent.kt only its pre-existing compose import error.
- **Not run:** ToolRegistryCatalogTest (needs Gradle), Gradle/Robolectric, CI, device. No test fails locally.

## Left / next steps
1. Owner decides: keep `ed3c75e7` on main, or revert it on main and merge this branch later.
2. Run under Gradle: `./gradlew testDebugUnitTest --tests 'com.blaze.assistant.policy.*' --tests '*ToolRegistryCatalogTest' --tests '*ConfirmGateTest' --tests '*CommandPolicyTest' --tests '*LocalEngineTest'`.
   Fix any tool ToolRegistryCatalogTest reports by declaring it in `policy/ActionClass.kt`.
3. Docs commit on main: overhaul.md Status "P2 under way: ✓ P2-SF-01 (ed3c75e7, 2026-11-13)", one Handoff line,
   Deviations rows (shadow in user chats; interim confirm card 2-min = denied; standing allows direct-only; manager-sourced
   jobs = BOT trigger; widget remove = DELETE; standing_permission = CREDENTIAL); 06-team-plan row `**P2-SF-01** ✓ ed3c75e7`;
   note P1-QA-01 still awaits owner shot review / phone checklist.
4. Known gap: a skipped approval leaves today's confirm card up until its 2-min timeout (Agent.confirm has no finally).
