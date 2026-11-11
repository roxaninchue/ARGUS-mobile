# P0 execution plan — Groundwork (0.52.4)

> Working plan for the P0 session that started 2026-11-11 22:25 CAT. Source of truth for scope stays `06-team-plan.md` §6.1 and `P0-HANDOFF.md`; this file says **how** each ticket is being done, in what order, and what "verified" means without a Gradle build. Updated as tickets land.

## Owner answers received this session (2026-11-11, Finn)
- **A-team code may be copied into argus-mobile under MIT** (P0-IL-02 precondition). A-team itself is not edited; the grant is recorded in `THIRD_PARTY_NOTICES.md` and `app/src/main/assets/legal/THIRD_PARTY_NOTICES.md`.
- **Gateway work goes on a new branch in `blazeinchue/Blaze-agent`, never its `main`.** No other GitLab repo is deleted or altered.
- Decisions D1–D11 stay at the recommended defaults (unchanged). D12 is now "branch-only write".

## Gateway check (Blaze-agent `gateway/`, 0.9.x, read 2026-11-11)
- `blaze_context` is already parsed by the live gateway (`gateway/src/relevance.ts`), and `GET /v1/models/live` exists (`gateway/src/index.ts`, `registry.ts`); `X-Blaze-Prefer/Avoid` and fallback headers are served by `chat.ts`.
- So P0 needs **no gateway change**; P0-API-01 is client-only. If a ticket turns up a gap, the fix goes on branch `overhaul/p0-gateway` in Blaze-agent and is listed here.

## Verification without a build (owner rule: no Gradle, no CI)
- Toolchain in the sandbox: JDK 17 (aarch64) + standalone kotlinc 2.0.21. `junit.jar` + `hamcrest.jar` at repo root.
- **Pure files + their JVM tests are compiled and run** with kotlinc (`kotlinc <pure srcs> <test> -cp junit.jar:hamcrest.jar -d out && java -cp out:junit.jar:hamcrest.jar:<kotlin-stdlib> org.junit.runner.JUnitCore <Test>`).
- Android/Compose glue gets a syntax + symbol pass by hand; Robolectric / screenshot tests are written to the repo's existing patterns and run first on the owner's "build".
- Every commit: title ends `[skip ci]`, pushed with `-o ci.skip`, straight to `main`.

## Order and parallelism
| Wave | Tickets (parallel) | Why |
|---|---|---|
| 1 | P0-TM-01 · P0-SH-01 · P0-API-01 · P0-QA-01 · P0-MG-01 · P0-IL-02 + P0-IL-03 | no deps between them |
| 2 | P0-AG-01 (needs TM-01 `Row`/`Bot`) · P0-SH-02 (needs SH-01 tokens) | deps |
| 3 | Integration: tick §6, coverage "How", STATUS.md, overhaul.md log, re-run every pure test together | one writer for shared docs avoids rebase fights |
| — | P0-IL-01 release | **waits for the owner's "build"** |

Shared bookkeeping files (`06-team-plan.md`, `STATUS.md`, `overhaul.md`) are written only in wave 3 by the integration lead; ticket commits touch code, tests and their own docs.

## Ticket approach
| Ticket | Approach | Pure tests run here | Written for "build" |
|---|---|---|---|
| P0-TM-01 | `team/Models.kt` (05 §9.1 verbatim enums + data classes, plus `DEFAULT_RULES`), `team/TeamJson.kt` (org.json-free pure encoder/decoder so it runs on the JVM), `team/TeamSchema.kt` (pure: the 05 §9.2 DDL as versioned migration steps), `team/TeamDb.kt` (SQLiteOpenHelper like `cache.db`, applies `TeamSchema`) | TeamJsonTest, TeamSchemaTest | TeamDbTest (Robolectric) |
| P0-AG-01 | `agent/TeamHooks.kt` (identity, tool subset, row sink), `agent/engines/BotEngine.kt` (05 §5.2 contract, events), `agent/engines/LocalEngine.kt` wraps `Agent.Session`; `Agent.kt` gets ≤ 20 lines to consult `TeamHooks` | LocalEngine event mapping on a fake client, Agent.kt line delta check | LocalEngineTest |
| P0-SH-01 | `ui/argus/ArgusTokens.kt` (pure numbers/colours from 04 §2), `ArgusType.kt`, `ArgusGlass.kt` (Haze + low-RAM/API<31 fallback rule as pure fn), `ArgusTheme.kt` (M3 mapping, `argus_look` default off), Haze dependency in `app/build.gradle.kts` | ArgusTokensTest, ContrastTest cases (3 grounds × glass levels) | ThemeEverywhereTest stays green |
| P0-SH-02 | `tools/gen-lucide.mjs` reads `a-team app/www/icons.js` → `ui/argus/icons/Lucide.kt`; `BotOrb.kt` Canvas (6 faces, OrbState), `ArgusMark` sphere; pure geometry in its own file | orb geometry/state test, generator determinism | OrbShotsTest |
| P0-API-01 | `blaze_context:"full"` on direct gateway calls in `Cloud.kt`; `LiveModels.kt` (port of a-team `models.js`: 5-min cache, lane pick, Prefer/Avoid, fallback headers) behind a flag; `ModelRoute.kt` consults it when on | LiveModelsTest (a-team evals model cases), CloudTest body field | — |
| P0-QA-01 | `docs/overhaul/specs.md` maps every A-team Playwright PASS line, A-team evals case and Bot HQ e2e step to a ticket; a-team `evals/` copied to `evals/` (Node, no deps), base URL = gateway | `node evals/run.mjs --suite refusals` syntax/dry-run | live run needs a token at run time |
| P0-MG-01 | `docs/overhaul/ateam-export.md` (argus-team-export.v1 schema + A-team backup mapping) + fixtures in `app/src/test/resources/ateam/` | JSON fixtures validate against the doc's rules | P1-MG-01 import |
| P0-IL-02 | `gateway/README.md` read-only banner; notices for Bot HQ (MIT), Lucide (ISC), Simple Icons (CC0), Haze (Apache-2.0), noVNC (MPL-2.0, later), A-team (MIT grant); mirror in `assets/legal/` | LegalAssetsTest / MoviesLegalTest literals re-checked | — |
| P0-IL-03 | `docs/overhaul/do-not-port.md` + `tools/check-do-not-port.sh` grep check | script runs clean | — |

## Status (updated as tickets land)
| Ticket | State | Commit |
|---|---|---|
| P0-TM-01 | in progress | |
| P0-AG-01 | waiting on TM-01 | |
| P0-SH-01 | in progress | |
| P0-SH-02 | waiting on SH-01 | |
| P0-API-01 | in progress | |
| P0-QA-01 | in progress | |
| P0-MG-01 | in progress | |
| P0-IL-02 | in progress | |
| P0-IL-03 | in progress | |
| P0-IL-01 | waits for "build" | |
