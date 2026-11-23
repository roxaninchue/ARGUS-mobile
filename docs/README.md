# Documentation index

Updated 2026-11-22. Start at the project root: STATUS.md (current state), HANDOFF.md (how to pick the work up),
AGENTS.md (rules), BUILDING.md (CI and builds), CHANGELOG.md (every version). Paths below are relative to this folder.

Note: every `.md` file under this folder is bundled into the app's built-in help search at build time.

## Live references

| File | What it is |
|---|---|
| [PC-REPLACEMENT.md](PC-REPLACEMENT.md) | Owner goal (2026-11-23): Argus as a PC replacement. Argus Desk, keyboard and mouse, desk apps, phases, Phase 0 removals |
| [MODEL-FIRST.md](MODEL-FIRST.md) | Owner rule (2026-11-16): build around the model, no keyword routing; guarded by `ModelFirstTest` |
| [LEAN-HARNESS.md](LEAN-HARNESS.md) | How a chat turn works since 0.55.0: short cached prompt, core tools, `find_tools` / `load_skill` |
| [SKILL-SHELF.md](SKILL-SHELF.md) | Skills and specialists reach the model as one-line shelves; the model picks |
| [VIEW-SHELF.md](VIEW-SHELF.md) | Views are offered to the model the same way (no view router) |
| [AGENTS.md](AGENTS.md) | Agent loop, background agents, parallel tools, blueprint orchestration (written for 0.52.0; the routing parts are retired, see its header) |
| [MEMORY.md](MEMORY.md) | Tiered memory (0.49.0): what is kept, what goes into a turn |
| [ARGUSQUERY.md](ARGUSQUERY.md) | Automatic judged facts per reply (0.51.0) |
| [TERMINAL-FIRST.md](TERMINAL-FIRST.md) | Terminal-first route (0.51.0). The route table predates MODEL-FIRST; read it with that rule in mind |
| [TERMINAL-CHECKPOINTS.md](TERMINAL-CHECKPOINTS.md) | Terminal snapshots and rollbacks |
| [BIOMETRIC-LOGIN.md](BIOMETRIC-LOGIN.md) | Biometric unlock, keys and what survives a reinstall |
| [BRAND.md](BRAND.md) | Argus brand tokens (palette, mark) |
| [glass-design-system.md](glass-design-system.md) | The glass design system (v0.31.0, phase 1) |
| [connect-icons.md](connect-icons.md) | App icons for Settings › Apps & connectors |
| [argus-link-messages.md](argus-link-messages.md) | Protocol: messages drafted on the PC, sent from the phone (v0.17.0) |
| [removed.md](removed.md) | One line per removed piece of code |
| [BUILD-LOCAL.md](BUILD-LOCAL.md) | Full local build in an aarch64 sandbox without KVM (written for 0.49.0) |
| [RENAME-ARGUS.md](RENAME-ARGUS.md), [RENAME-ARGUS-PLAN.md](RENAME-ARGUS-PLAN.md) | The rename to Argus and its phase plan; the guard script and baseline live in `tools/` |
| [rename/](rename/) | Rename inventory (2026-11-19) |
| [next-release.md](next-release.md) | Search-quality fixes noted 2026-11-14 (local results, low success rate, search API shortlist). Not verified whether any were done since the web search moved into the gateway loop |

## Overhaul (`overhaul/`)

| File | What it is |
|---|---|
| [overhaul/account-spaces-plan.md](overhaul/account-spaces-plan.md) | Account spaces (2026-11-22, plan only): one data space per account; fixes the account-switch data leak |
| [overhaul/living-ui-0.3.0-plan.md](overhaul/living-ui-0.3.0-plan.md) | Living UI 0.3.0 plan (2026-11-22, not started) |
| [overhaul/overhaul.md](overhaul/overhaul.md) | Living UI design direction (2026-11-14) and mockups |
| [overhaul/BUILDING.md](overhaul/BUILDING.md) | Owner rule (2026-11-13): checklist to build clean on the first try |
| [overhaul/do-not-port.md](overhaul/do-not-port.md) | What is deliberately not ported from Bot HQ and A-team; checked by `tools/check-do-not-port.mjs` |
| [overhaul/ateam-export.md](overhaul/ateam-export.md) | A-team export format and backup mapping (used by the import / backup code) |
| [overhaul/specs.md](overhaul/specs.md) | Generated spec index (by `tools/gen-specs.mjs`); checked by the evals tests |
| [overhaul/field-tests-2026-11.md](overhaul/field-tests-2026-11.md) | Field test results table (T1–T14) |
| [overhaul/landscape-audit.md](overhaul/landscape-audit.md) | Every screen in all rotations (2026-11-15) |
| [overhaul/p1-phone-checklist.md](overhaul/p1-phone-checklist.md) | P1 real-phone checklist |
| [overhaul/01-bothq-inventory.md](overhaul/01-bothq-inventory.md) … [overhaul/06-team-plan.md](overhaul/06-team-plan.md) | The six slice docs of the overhaul plan (2026-11-11): Bot HQ and A-team inventories, the Argus map, design and IA, agent ecosystem, team plan. The plan is not approved as of 2026-11-22 |
| [overhaul/P0-PLAN.md](overhaul/P0-PLAN.md), [overhaul/P0-HANDOFF.md](overhaul/P0-HANDOFF.md) | P0 (0.52.4) plan and handoff; P0 is complete. History |

## History and superseded docs

Moved to [archive/](archive/):

| File | What it was |
|---|---|
| [archive/HANDOFF-gallery.md](archive/HANDOFF-gallery.md) | Gallery handoff (v0.30.0): phone test list and next steps (moved from the root on 2026-11-22) |

Superseded but kept at their old paths, each with a note at the top. The rename guard counts the old product name per
file path, so these files cannot move without raising the count:

| File | What it was |
|---|---|
| `../HANDOFF-2026-11-22-lean-cloud-loop.md` | Lean harness + cloud tool loop handoff (0.55.0 / 0.55.1, gateway 0.9.38 / 0.9.39) |
| `../HANDOFF-2026-11-15-round2.md` | 0.53.4 round 2 and Living UI 0.2.0 handoff |
| `../HANDOFF-argus-hark-design.md` | Argus × Hark design branch (0.52.3) |
| `../HANDOFF-argus-3d-home.md` | Living 3D Home branch (0.52.2) |
| [HANDOFF-0.52.md](HANDOFF-0.52.md) | 0.52.1 branch handoff |
| [PLAN-reliable-agent.md](PLAN-reliable-agent.md) | Reliable-agent plan written for 0.50.0 (2026-11-01) |
| [ideas-0.33.1.md](ideas-0.33.1.md) | Verdicts on 33 ideas (v0.33.1) |
| `../HANDOFF.md`, bottom part | Media apps handoff (v0.30.1), appended below the current handoff |

Other root files: `../P.H.A.md` (2026-11-09/10 report on how Hark works, a reference for Argus),
`../STORE_CLEANROOM.md` (clean-room record for the Store tab), `../overhaul.md` (overhaul plan, harness rules, handoff log).
