# Do-not-port register (P0-IL-03)

> What ARGUS deliberately does **not** bring over from Bot HQ (BH) and A-team (AT), why, and what replaces it.
> Checked by `node tools/check-do-not-port.mjs` (no CI: run by hand from the repo root; exits 1 on a hit). It scans
> `app/src/main`, `tools/` and `evals/` (not `evals/reports/`, not the shipped licence copy) and fails when a row's
> pattern appears, or when this table and the script disagree on which rows are checked.
> Sources: `01-bothq-inventory.md` (Bot HQ @HEAD), `02-ateam-inventory.md` (a-team @7c4b459a), `06-team-plan.md` §7.
> First run 2026-11-12: 13 rules, 0 hits after one fix (a `composio` comment in `team/Models.kt`).

| Row | What is not ported | Source | Why | Replaced by | Code check |
|---|---|---|---|---|---|
| BH-75 | Google sign-in through Composio Gmail | BH `lib/auth.ts`, Composio | ARGUS has its own account; Composio never runs in the app (D5) | ARGUS account in Onboarding; apps via argus-connect (P4) | yes |
| BH-76 | `ALLOWED_EMAILS` allow-list | BH server env | Server-only gate | Gateway waitlist | yes |
| BH-77 | Dev login, owner key, one-time sign-in links (`/auth/dev`, `/auth/owner`, `/auth/link/:token`, `sign-in-link.sh`) | BH `lib/auth.ts`, `scripts/` | Dev-only | Desktop link QR pairing (existing AM) | yes |
| BH-78 | 30-day httpOnly cookie sessions in SQLite | BH `lib/auth.ts` | A phone app has no cookie session | `bz_` ARGUS token in the Keystore | yes |
| BH-86 | "Grokbot" branding (cookie, db file, localStorage keys, title) | BH web + server | Not our name | ARGUS naming | yes |
| BH-ART | Bot avatar artwork (`web/artwork/shapes/*.svg`, `shapes.gen.ts`, `BotAvatar.tsx`) | BH web | Derived from Grok Bot design, **not MIT** | ARGUS orbs drawn from scratch (`ui/argus/OrbGeometry.kt`, P0-SH-02) | yes |
| BH-DEAD-1 | `agent/subagents.ts` (createSubagent…) as written: never wired to a tool or route | BH server | Dead code | Real subagents in P5-TM-03 | yes |
| BH-DEAD-2 | `/memories/consolidate` and `/compact` stub routes | BH server | Stubs | Compact in the bot profile (P3-SH-01); memory dedupe on save (P3-AG-02) | yes |
| BH-DEAD-3 | Unused tables `llm_items`, `pending_turns`, `memory_jobs`, `compactions` | BH `db.ts` | Never read or written | `team.db` (P0-TM-01) has none of them | yes |
| BH-DEAD-4 | Draft card UI with no emitter | BH `Cards.tsx DraftCard` | Renders but nothing sends it | A real DraftCard for every SEND (P2-SF-02) | no (built new) |
| BH-DEAD-5 | Disabled idle-suspend watcher | BH server | Off in BH | Lease idle stop (P7-CP-08) | no (built new) |
| BH-DEAD-6 | "Bot templates: coming soon" button | BH web | Placeholder | New bot → From a template (P1-TM-04) | no (built new) |
| BH-DEAD-7 | `citations` on messages, never filled | BH types | Empty | Real reply sources (P1-SH-03) | no (built new) |
| BH-NOTE | Bot `mode` (personal / teammate), stored but unused in BH | BH bot record | **Kept on purpose**: `team.db bots.mode` (P0-TM-01) | Given meaning by the templates in P1-TM-04 | no (kept) |
| AT-01 | WebView shell + `Native` JS bridge | AT `MainActivity.java`, `www/` | ARGUS is native Compose | Native services (all phases) | yes |
| AT-57 | On-phone Connections card, tokens exported to the shell env, `gmail_list` / `cal_list` shell helpers (`~/.argus/env`) | AT v2.16b, removed in v2.18 | Removed upstream; tokens in a shell env are unsafe | argus-connect (P4); AM's own `gmail_read` / `gmail_search` tools stay | yes |
| AT-67 | No-Gradle build (`app/build.sh`: aapt2 → javac → d8 → apksigner) | AT `app/build.sh` | ARGUS builds with Gradle | Gradle (existing) | yes |
| AT-CACTUS | `cactus.wasm` + `cactus.js` + `needle-worker.js` WebView runtime (37 MB) | AT `www/` | No WebView; APK size | Native Needle in `FastLane.kt` / `libneedle.so` (R28, P1-TM-06). The `needle3.cact` model itself is used | yes |

## Adding a row
Add the row here with `yes` in the last column, add a rule with the same id to `RULES` in
`tools/check-do-not-port.mjs`, run the script, and commit both together. A `no` row is a feature built new rather
than banned, so there's nothing to grep for.
