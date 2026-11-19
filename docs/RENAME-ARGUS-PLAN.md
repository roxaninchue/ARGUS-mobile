# Argus Rename Plan: retiring "Blaze" from argus-mobile

Repo: `gitlab.com/blazeinchue/argus-mobile` (GitHub mirror `roxaninchue/ARGUS-mobile`)
Baseline: `main` @ `1fc72902` (2026-11-19)
Goal: every line of code, resource, script and document says **Argus** instead of **Blaze**, with no user losing data, permissions, sign-in, updates or sync.

---

## 1. Where we stand

The 0.52.1 release already did the **user-facing** rename (see `docs/RENAME-ARGUS.md`): `BrandName.NAME = "Argus"`, ~1,650 Kotlin literals routed through `${BrandName.NAME}`, launcher/activity labels, wake word ("Hey Argus", with "Hey Blaze" still accepted), logo, splash, theme, tier labels (Insight / Oracle / Panoptes). What it **deliberately left alone** is everything below the surface, and that is what this plan covers.

### Inventory (case-insensitive "blaze", excluding `.git`, `.gradle`, `.kotlin`, `build/`)

| Area | Files | Notes |
|---|---|---|
| `app/` | 1,565 | 918 main `.kt` + 571 unit-test `.kt` + 3 androidTest under `com/blaze/assistant` |
| `blazerouter/` (Go) | 40 | module `github.com/blazeinchue/blaze-desktop`, ships as `libblazerouter.so` |
| `tools/` | 34 | CI scripts, smoke, phone-bridge, generators |
| `gateway/` (Cloudflare Worker, TS) | 29 | `blaze-gateway`, `blaze-auth`, `X-Blaze-*`, `blaze_*` fields |
| `docs/` | 29 | incl. `BLAZEQUERY.md`, `blaze-link-messages.md`, `overhaul/*` |
| `.github/` | 8 | workflows, secrets `BLAZE_MOBILE_TOKEN`, release assets `Blaze-v*.apk` |
| `store/`, `storeapi/`, `evals/`, `wgproxy/` | 14 | catalog, UA strings, Go module `github.com/blazeinchue/Blaze-agent/wgproxy` |
| Root docs + build files | 16 | README, AGENTS, STATUS, HANDOFF*, CHANGELOG, BUILDING, LICENSE, `.gitlab-ci.yml`, `settings.gradle.kts` (`rootProject.name = "Blaze"`) |
| **Total** | **1,735 files / 14,794 lines** | |

Most frequent forms: `Blaze` (3,331), `blaze` (829), `com.blaze.assistant.*` (thousands of imports), `LocalBlaze` (~450), `BlazeShapes` (332), `Role.BLAZE` (154), `blaze.db` (168), `BlazeRouter` (167), `blaze-auth` (125), `X-Blaze-*` (~95), `blazeinchue.pages.dev` (52).

### Risk classes

Every occurrence falls into one of these. The phases are ordered from A (zero risk) to H (never).

| Class | What | Risk if renamed blindly |
|---|---|---|
| **A. Prose** | Docs, comments, KDoc, handoff notes | None |
| **B. Leftover user-visible text** | ~200 Kotlin string literals still saying "Blaze", e.g. `"Hello from Blaze"` (MainActivity), "Blaze Inferno" (Spotlight name in ModelChoice), i18n strings that quote folder names (`Music/Blaze`, `Movies/Blaze`) | Low; some are tied to class F (folder names) |
| **C. Pure code identifiers** | 66 `Blaze*` types (`BlazeShapes`, `BlazeQuery`, `BlazeDb`, `BlazeDocs`, `BlazeColors`, `BlazeDialog`, `LocalBlaze`…), resource names (`Theme.Blaze`, `ic_stat_blaze`, `widget_blaze`), test classes, `BLAZE_SHOTS` | Compile errors only; caught by build + tests |
| **D. Kotlin package / namespace** | `com.blaze.assistant` → `com.argus.assistant` (also changes `R`/`BuildConfig` package) | Breaks **system-bound components** (see Phase 4), WorkManager jobs, ProGuard keeps |
| **E. Persisted on-device data** | `blaze.db`, prefs `blaze`, `blaze_tasks`, `blaze_phone`, `blaze_browser_trackers`, files `blaze_inflight.txt`, `blaze_crash_pending`, table `blaze_docs`, chat JSON `"role":"BLAZE"`, persona id `blaze`, `Duo.Who.BLAZE`, notification channels `blaze_custom/blaze_jobs/blaze_routine`, shared folders `Music/Blaze`, `Movies/Blaze`, `Download/Blaze`, `Pictures/Blaze` | **Silent data loss** for existing installs |
| **F. Wire protocol** | 16 `X-Blaze-*` headers (Prefer, Provider, Model, Route, Avoid, Reserve, Task, Token, Turn, Reply-Lang…), body fields `blaze_task`, `blaze_avoid`, `blaze_family`, `blaze_models_busy`, User-Agents (`Blaze/1`, `Blaze-Android`, `Blaze-Store`), Jellyfin client id | Old app builds ↔ new gateway break (and vice versa) |
| **G. Infrastructure & hosting** | Workers `blaze-gateway`, `blaze-auth`, D1 `blaze-gateway`, workers.dev subdomain `blaze-app`, Pages `blazeinchue.pages.dev` (App Links), `blazeinchue.netlify.app`, deep link `blaze://add-source` (39 uses), GitLab/GitHub repo + namespace names, Go module paths, CI secrets | Sign-in, App Links, update checks, CI break |
| **H. Never rename** | `applicationId = "com.blaze.assistant"` and the release signing key/lineage | Every existing install stops receiving updates; becomes a different app |

---

## 2. Ground rules (apply to every phase)

1. **applicationId stays `com.blaze.assistant` forever.** It is invisible to users. Changing it = new app, no upgrade path.
2. **Read old, write new.** Anything persisted or on the wire: the new build accepts both names; it writes the new name only once every reader understands it.
3. **One phase = one branch = one merge.** Each phase lands green on `build.yml` (full unit suite on the GitHub mirror) before the next starts. No mixed phases in a commit; mechanical renames in their own commits so `git log --follow` and review stay sane.
4. **Mechanical, scripted, repeatable.** Extend `work/rename_argus.py` (used for 0.52.1) into `tools/rename/` with one script per phase and an **allowlist** (`tools/rename/keep-blaze.txt`) of occurrences that must stay. Re-running a script on a clean tree must be a no-op.
5. **A guard keeps it from coming back.** `tools/check-no-blaze.mjs` (modelled on `check-do-not-port.mjs`) fails CI on any new "blaze" outside the allowlist.
6. **Upgrade test before every release.** Install the last shipped APK, create real state (chats, a routine, a download, a placed widget, accessibility + assistant enabled, signed in), then install the new APK over it and check nothing was lost (checklist in §5).
7. **Don't rewrite history.** Past CHANGELOG entries, git history, tags and released APK names stay as they were.

---

## Phase 0: Prep and guardrails (½ day)

- Branch `rename/phase-0`.
- `tools/rename/inventory.sh`: the counts in §1, by class; output saved to `docs/rename/inventory-<date>.txt` so each phase shows its delta.
- `tools/rename/keep-blaze.txt`: start with classes E–H verbatim (applicationId, `blaze.db`, prefs names, `X-Blaze-*`, hosts, `blaze://`, channel ids, folder names, `Role.BLAZE` wire value, persona id, legacy wake phrases, `BrandName.OLD`).
- `tools/check-no-blaze.mjs` + test, wired into `build.yml` in **report-only** mode (flip to failing at the end of Phase 3).
- Build an **upgrade fixture**: a script (`tools/smoke/upgrade-from.sh <old.apk>`) that installs the previous release, seeds state via adb/the dashboard, upgrades, then dumps chats/prefs/db row counts for diffing.
- Record the baseline: unit suite green, APK size, `blaze.db` schema hash.

**Exit:** inventory committed, guard running in report mode, upgrade fixture works against the current release.

---

## Phase 1: Documents and comments (class A) (1 day)

Zero runtime effect; do it first so every later diff is about code.

- Root: `README.md`, `AGENTS.md`, `STATUS.md`, `BUILDING.md`, `HANDOFF*.md`, `P.H.A.md`, `overhaul.md`, `STORE_CLEANROOM.md`, `THIRD_PARTY_NOTICES.md` (keep upstream attribution lines that name the Blaze Desktop repo until Phase 7 renames it), `LICENSE.md` (copyright holder: change only if the legal entity changes; Blaze is the company, Argus the product).
- `CHANGELOG.md`: add the rename entry; **leave past entries untouched**.
- `docs/`: rename files `BLAZEQUERY.md` → `ARGUSQUERY.md`, `blaze-link-messages.md` → `argus-link-messages.md` (with `git mv`), fix inbound links. `docs/overhaul/*` and dated handoffs are historical: add a one-line "written before the Argus rename" header instead of rewriting.
- `app/blaze-docs/` (the app's built-in self-knowledge): rewrite prose now; the folder name itself moves in Phase 3 together with the Gradle task that packs it.
- Comments and KDoc across `.kt`, `.ts`, `.go`: scripted, comments only (the script must skip string literals and code).
- Update `docs/RENAME-ARGUS.md` to point at this plan.

**Exit:** `grep -ri blaze docs README.md AGENTS.md …` only hits allowlisted literals; build green.

---

## Phase 2: Finish the user-visible sweep (class B) (1 day)

- Triage the ~200 remaining `"…Blaze…"` Kotlin literals into: user-visible (route through `BrandName.NAME`), legacy-compat (keep, add to allowlist with a reason, e.g. `BrandName.OLD`, legacy wake phrases, parsing old chats), or class E/F/G (defer to that phase).
  - Known user-visible leftovers: `MainActivity` "Hello from Blaze", Spotlight/self-map name "Blaze Inferno" in `ModelChoice`, "Things Blaze is doing" (SimplePages), Home logic fallbacks, Duo/OnePersona labels shown in UI.
- `assets/i18n/*.json` (20 locales): the only remaining hits are strings quoting folder paths (`Music/Blaze`, `Movies/Blaze`). Change them **in Phase 5**, in the same commit as the folder move, so text and reality never disagree.
- `assets/skills/*/SKILL.md`, `assets/specialists/*.md`, `assets/persona-v1.json`, `assets/store/catalog.json`, `assets/legal/*`: prose only. Skill **folder names** (`blaze-voice`, `blaze-task-workflow`, `web-design-blaze-classic`…) are ids the agent loads by name; rename them in Phase 3 with an alias map.
- `assets/movies/blaze-official/`: folder name is an id; Phase 3.

**Exit:** a user can't find "Blaze" anywhere in the UI except where they wrote it themselves (old chats) or in folder paths pending Phase 5. Screenshot tests (`ShotsTest`, `BLAZE_SHOTS=1`) pass.

---

## Phase 3: Internal identifiers that nothing persists (class C) (2–3 days)

All compile-time; the compiler and the 571 unit-test files are the safety net.

Order (each its own commit):
1. **Compose/UI tokens:** `BlazeShapes`, `BlazeColors`, `BlazeTheme`, `BlazeBrush`, `BlazeIconStyle`, `BlazeDesign`, `LocalBlaze` → `ArgusShapes`, … `LocalArgus`. Note `ui/argus/ArgusTokens` / `ArgusType` already exist: **merge**, don't duplicate; pick one token source.
2. **UI components:** `BlazeDialog`, `BlazeDialogCard`, `BlazeMenu`, `BlazeMenuCard`, `BlazeTextField`, `BlazeButton`, `BlazeSwitchTrack`, `BlazeTopPill(Model)`, `BlazeGlass`, `BlazeChatBar`, `BlazeBubble`, `BlazeAvatar`, `BlazeComposerHome`, `BlazeTextToolbar`, `BlazeView`.
3. **Logic classes not referenced by the system or by persisted data:** `BlazeQuery` (+ `docs/BLAZEQUERY.md` already moved), `BlazeDocs`, `BlazeRouter` (Kotlin wrapper only; the `.so` name moves in Phase 7), `BlazeDj`, `BlazeReply`, `BlazeSpeed`, `BlazeFX`, `BlazeStart`, `BlazeOS`/`BlazeOsCleanup`, `BlazeThread`, `BlazeAccount`, `BlazeCamera`.
   - `BlazeDb` class → `ArgusDb` is fine; the **file name `blaze.db` stays** until Phase 5.
4. **Enum constants:** `Role.BLAZE` → `Role.ARGUS`, `LivingModel.Role.BLAZE`, `Duo.Who.BLAZE`. **Serialization must not change yet:** `ChatJson.roleOf` and `Agent` load/save use `role.name`. Before renaming, introduce an explicit `wire` value (`"BLAZE"`) on the enum and serialize that; `roleOf` keeps accepting `BLAZE`, `ARGUS`, and `BrandName.UPPER`. Dashboard JSON and `TeamHooks.message` use the same wire value.
5. **Resources:** `Theme.Blaze*` → `Theme.Argus*`, `ic_stat_blaze` → `ic_stat_argus`, `widget_blaze.xml`, `blaze_interaction.xml`, `blaze_recognizer.xml`, `blaze_widget.xml` → `argus_*`. Resource names aren't persisted by Android, **but** check `RemoteViews` in placed widgets and any `R.drawable` id written to prefs or notifications (they're ints and regenerate; safe if nothing stores them).
6. **Asset ids with aliases:** skill folders `blaze-*` → `argus-*`, `app/blaze-docs` → `app/argus-docs` (update the Gradle task at `app/build.gradle.kts` L81–84), `assets/movies/blaze-official` → `argus-official`. Add a tiny alias map (`"blaze-voice" → "argus-voice"`) wherever a skill/collection id can be stored (saved chats, routines, user prefs) so old references resolve.
7. **Build/test plumbing:** `rootProject.name = "Argus"`, `BLAZE_SHOTS` → `ARGUS_SHOTS` (read both for one release), system property `blaze.shots` → `argus.shots`, test class names (`BlazeDbTest`, `BlazeQueryEngineTest`, `BlazeThreadTest`, `BlazeOsRemovedTest`…).

Then flip `check-no-blaze` to **failing** for everything outside the allowlist.

**Exit:** build + full unit suite green on GitHub `build.yml`; ShotsTest green; upgrade fixture shows identical chats/prefs/db.

---

## Phase 4: Kotlin package and Android namespace (class D) (2 days + device test)

`com.blaze.assistant` → `com.argus.assistant` for all 1,492 Kotlin files and `namespace` in `app/build.gradle.kts`. **`applicationId` stays `com.blaze.assistant`.**

This is mechanical (move dirs, rewrite `package`/`import`, `R`/`BuildConfig` imports) but Android identifies several things by **fully qualified class name**, which this changes. Each needs a compatibility measure:

| System binding | Today | What breaks | Measure |
|---|---|---|---|
| Accessibility service | `.phone.BlazeAccessibilityService` | User's grant is per ComponentName: phone control silently turns off | Keep a thin class at the **old FQCN** (`com.blaze.assistant.phone.BlazeAccessibilityService : ArgusAccessibilityService()`) declared in the manifest. Never rename the declared component. |
| Default assistant | `.assist.BlazeInteractionService`, `BlazeSessionService`, `BlazeRecognitionService` | Argus stops being the default assistant / voice input | Same: old-FQCN shims stay declared |
| Quick Settings tile | `.assist.BlazeTile` | Tile disappears from the user's QS panel | Old-FQCN shim |
| Home-screen widget | `.widget.BlazeWidget` receiver | Placed widgets vanish | Old-FQCN shim receiver |
| Launcher entry / pinned shortcuts | `.MainActivity` | Home icon and pinned shortcuts break | `<activity-alias android:name="com.blaze.assistant.MainActivity" android:targetActivity=…>` |
| Media session / Android Auto | `.mediaapps.MusicService` | Media resumption entry lost | Shim or alias |
| WorkManager | 10 `CoroutineWorker`s (StandupWorker, TaskWorker, CloudSyncWorker, MediaDownloadWorker…) | Already-enqueued work references the old class name and fails to instantiate | Custom `WorkerFactory` mapping `com.blaze.assistant.*` names → new classes, plus re-enqueue of periodic work on first run of the new version |
| Alarms / PendingIntents | explicit components | Pending alarms fire into nothing | Re-schedule on upgrade (`MY_PACKAGE_REPLACED` receiver) |
| ProGuard/R8 | `-keep class com.blaze.assistant.** …` (e.g. MindReviewWorker, MediaDownloadWorker, DesignBridge) | Release build strips/obfuscates needed classes | Update rules to the new package **and** keep the shims |
| `taskAffinity` strings (`com.blaze.assistant.music`, `.terminal`, `.browser`…) | free-form strings | Nothing persisted; cosmetic | Rename freely |
| FileProvider | `${applicationId}.files` | uses applicationId, unaffected | none |

JNI: no `Java_com_blaze_*` symbols exist (checked), so native code is unaffected.

Do it as: commit 1 = shims + alias + WorkerFactory against the **old** package (behaviour-neutral, testable alone); commit 2 = the mechanical move.

**Exit:** unit suite green; release build (R8) green; **on-device upgrade test** confirms accessibility still enabled, still default assistant, widget still placed, QS tile still present, a routine scheduled before upgrade still fires, pinned shortcut still opens.

---

## Phase 5: On-device persisted data (class E) (2–3 days + device test)

Pattern for everything here: a one-time `RenameMigration` runs at app start (before anything opens the stores), guarded by a pref flag `rename_migrated=1`, idempotent, and leaves the old artefact readable for at least two releases.

| Item | Plan |
|---|---|
| `blaze.db` (+ `-wal`, `-shm`) | **Option A (recommended): keep the file name.** It's invisible; renaming a live SQLite file risks corruption for no user benefit. Put `blaze.db` in the permanent allowlist with a comment. **Option B:** close → checkpoint WAL → copy to `argus.db` → integrity check + row counts match → switch → delete old on the *next* release. |
| Table `blaze_docs` / `blaze_docs_fts` | Rebuilt from assets on every update anyway: rename freely (drop old table in the same migration). |
| SharedPreferences `blaze`, `blaze_tasks`, `blaze_phone`, `blaze_browser_trackers` | Copy all keys to `argus*` files, then read from new. Keep reading old as fallback for 2 releases, then delete. Prefs **keys** containing `blaze_` (e.g. `blaze_cloud_url`, `blaze_self`) migrate the same way. |
| Files `blaze_inflight.txt`, `blaze_crash_pending`, others under filesDir | Rename on first run (`File.renameTo`, same volume, atomic). |
| Chat history `"role":"BLAZE"`, persona id `blaze`, `Duo.Who` id, speaker labels | **Keep reading forever** (old exports/backups exist). Start **writing** `ARGUS` only in the release *after* the reader shipped, so a user who downgrades one version can still open chats. |
| Notification channels `blaze_custom`, `blaze_jobs`, `blaze_routine` | **Keep the ids** (allowlist). New ids create new channels and wipe the user's per-channel sound/importance choices. Only the visible channel *names* say Argus (already via BrandName). |
| Shared-storage folders `Music/Blaze`, `Movies/Blaze`, `Download/Blaze`, `Pictures/Blaze` (+ `SaveFileAndroid.FOLDER`, `getExternalFilesDir(…)/Blaze`) | New saves go to `…/Argus`. Library views scan **both** folders. Optional background move via MediaStore `RELATIVE_PATH` update (no permission needed for files we own), never delete the old folder automatically. Update the i18n strings that quote these paths in this same commit. |
| Saved tier picks / model choices | Already migrated in 0.52.1 (`ModelChoice.migrate`). Wire ids spark/flare/inferno stay (see Phase 6). |
| Skill / collection ids stored in routines or chats | Resolve through the alias map from Phase 3. |
| Cloud sync payloads (`CloudSyncWorker`) | Anything synced is also class F: the server must accept both shapes before the app writes new ones. |

**Exit:** upgrade fixture diff is empty (same chats, same prefs values, same download library); a fresh install never creates a `blaze*` artefact except allowlisted ones.

---

## Phase 6: Wire protocol: app ↔ gateway (class F) (2 days, staged over 2 releases)

The app and `gateway/` ship separately and old APKs stay in the wild, so this is a **three-step expand/contract**:

1. **Gateway expands (deploy first).** In `gateway/src/{chat,providers,router,util}.ts`: read `X-Argus-*` **or** `X-Blaze-*` for every header (Prefer, Provider, Model, Route, Avoid, Reserve, Task, Token, Turn, Reply-Lang, Fallback, Normalized, Rules, TTFT-Ms, Tools-Dropped, Prefer-Status); accept `argus_task`/`blaze_task`, `argus_avoid`/`blaze_avoid`, `argus_family`/`blaze_family`; on responses **emit both** header names. One helper (`hdr(req, "Prefer")`) instead of 95 scattered literals. Gateway tests cover both spellings. Deploy via `gateway.yml`.
2. **App switches.** Send `X-Argus-*` and `argus_*` only; read either on responses. User-Agent `Argus/1 (Android)`, `Argus-Android`, `Argus-Store` (check `store/` and any provider that allowlists UAs first). Jellyfin client id: changing it registers a new device on the user's Jellyfin server and drops the old session; either keep `"Blaze Android"` (allowlist) or accept one re-login and say so in the release notes.
3. **Gateway contracts.** After the minimum supported app version is ≥ the release from step 2 (gateway can log the share of `X-Blaze-*` requests), drop the old names.

Tier wire ids `spark/flare/inferno` (`tiers.ts`, `blaze_family`) are a separate, optional decision: they aren't "Blaze" and renaming them buys nothing; recommend leaving them.

**Exit:** gateway serves old and new apps simultaneously with no error-rate change; dashboards/admin unaffected.

---

## Phase 7: Infrastructure, hosting, repos, CI (class G) (2–4 days, coordinated)

Best scheduled with the **Workers Paid upgrade planned for December 2026**, since several Worker changes land at the same time anyway.

### Cloudflare
- **workers.dev subdomain `blaze-app`** is account-wide: changing it changes *every* Worker URL at once. Don't. Instead put the Workers on a **custom domain** (e.g. `api.<argus-domain>`, `auth.<argus-domain>`) and point the app at that; old `*.blaze-app.workers.dev` URLs keep working for old builds.
- Workers `blaze-gateway` → `argus-gateway`, `blaze-auth` → `argus-auth`: deploy new names alongside, keep the old Workers as thin proxies (or keep the old routes) until old app versions age out. `argus-connect.blaze-app.workers.dev` already exists, same pattern.
- **Service binding** `service = "blaze-auth"` in `wrangler.toml` and the **shared KV namespace** (same id as Blaze-dekstop/cloud) must be renamed in lockstep with the desktop repo, or left as-is (internal names, allowlisted).
- D1 database `blaze-gateway`: D1 can't be renamed; it's internal. Keep it (allowlist) or create `argus-gateway`, export/import, switch `database_id`. Recommend keep.
- Secrets/env (`ADMIN_EMAILS`, `ADMIN_TOKEN`) are name-neutral; the admin page text can say Argus.

### Pages / App Links / deep links
- `blazeinchue.pages.dev` (52 refs, App Links for `/add-source`) and `blazeinchue.netlify.app`: publish the site on the new host, serve `/.well-known/assetlinks.json` there (same package `com.blaze.assistant` + signing cert), add the new host's intent-filters, **keep the old ones** and make the old site redirect.
- Deep link scheme: add `argus://add-source` next to `blaze://add-source` (39 uses); generate new links with `argus://`; keep accepting `blaze://` indefinitely (QR codes and shared links are already out there).

### Native / Go
- `blazerouter/` → `argusrouter/`, `libblazerouter.so` → `libargusrouter.so` (update `BlazeRouter.binary()`, `keepDebugSymbols`, `.gitlab-ci.yml` L49–61, the GitHub build). The `.so` is shipped inside the APK, so old/new can't mismatch. If the router keeps state in a data dir named after itself, migrate it like Phase 5.
- Go module paths `github.com/blazeinchue/blaze-desktop` (router, vendored from Blaze Desktop) and `github.com/blazeinchue/Blaze-agent/wgproxy`: change only together with the upstream repos, otherwise every future sync from desktop becomes a conflict. Until then, allowlist.

### Repos and CI
- GitLab namespace `blazeinchue` and GitHub `roxaninchue/ARGUS-mobile`: the repo is already named argus-mobile. Renaming the GitLab **user/namespace** creates redirects for web/git but **breaks the push mirror and any hard-coded API paths** (e.g. `ToolLeak.kt` links to `gitlab.com/blazeinchue/Blaze-dekstop`). Optional; if done, update the mirror config and every URL in the same change.
- `.gitlab-ci.yml`: branch rules `feat/blaze-0.51`/`0.52` (historical, drop or leave), artifact name `blaze-units-*` → `argus-units-*`, `BLAZE_SHOTS` (already dual-read from Phase 3).
- `.github/workflows/*`: release assets `Blaze-v$VER.apk` → `Argus-v$VER.apk`, release titles. **Before changing asset names, confirm the in-app updater / any download page doesn't match on `Blaze-v`** (none found in `app/src/main`, but check the Pages site and store catalog). Secret `BLAZE_MOBILE_TOKEN` → add `ARGUS_MOBILE_TOKEN`, read either, then remove the old. Private plug-in repo `Blaze-mobile` → rename on GitHub (redirects work for `actions/checkout`), update the workflow.
- `store/catalog.json`, `evals/`, `tools/` scripts: names, UAs, paths.

**Exit:** new and old app builds both sign in, sync, open App Links and get updates; CI and release pipeline produce `Argus-v*.apk`; mirror still syncs.

---

## Phase 8: Contract and clean up (after 2 releases)

- Remove the read-old fallbacks whose window has passed: old prefs files, old file names, `BLAZE_SHOTS`, old CI secret, gateway `X-Blaze-*` acceptance (once telemetry shows ~0), old Worker proxies, old Pages site (keep redirect).
- **Never remove:** applicationId, signing lineage, accessibility/assistant/widget/tile/launcher **shim classes at the old FQCNs**, `blaze://` deep-link acceptance, reading `"role":"BLAZE"` in chats, notification channel ids, the scan of old `…/Blaze` media folders (cheap, protects old downloads).
- Shrink `keep-blaze.txt` to the permanent list and document each entry's reason in `docs/RENAME-ARGUS.md`.

---

## 3. Permanent allowlist (expected end state)

| Stays "blaze" | Why |
|---|---|
| `applicationId com.blaze.assistant` | Update path for every install |
| Old-FQCN shim classes (`com.blaze.assistant.phone.BlazeAccessibilityService`, `…assist.Blaze{Interaction,Session,Recognition}Service`, `…assist.BlazeTile`, `…widget.BlazeWidget`, `…MainActivity` alias) | System grants, defaults and placed widgets are keyed by ComponentName |
| Notification channel ids `blaze_*` | Keeps users' channel settings |
| `blaze://` deep-link intent filter, old App Links hosts | Links and QR codes already in the wild |
| Reading `BLAZE` role / `blaze` persona / "Hey Blaze" wake phrase, `BrandName.OLD` | Old chats, backups, muscle memory |
| Scanning `Music/Blaze`, `Movies/Blaze`, `Download/Blaze`, `Pictures/Blaze` | Old downloads stay visible |
| `blaze.db` (if Option A), D1 `blaze-gateway`, workers.dev subdomain `blaze-app` | Internal, renaming is risk without benefit |
| Upstream Go module paths (until upstream renames) | Keeps desktop syncs clean |

Everything else: Argus.

---

## 4. Effort and order at a glance

| Phase | Scope | Effort | Ships to users? | Needs device test |
|---|---|---|---|---|
| 0 Guardrails | scripts, guard, upgrade fixture | ½ day | no | fixture only |
| 1 Docs & comments | A | 1 day | no | no |
| 2 UI leftovers | B | 1 day | yes | screenshots |
| 3 Internal identifiers | C | 2–3 days | yes | upgrade fixture |
| 4 Package/namespace | D | 2 days | yes | **yes** |
| 5 On-device data | E | 2–3 days | yes | **yes** |
| 6 Wire protocol | F | 2 days over 2 releases | gateway + app | gateway tests |
| 7 Infra/repos/CI | G | 2–4 days | yes | **yes** (sign-in, App Links) |
| 8 Contract | cleanup | ½ day | yes | upgrade fixture |

Phases 1–3 can ship in one release. 4 and 5 should each be their own release with a device upgrade test. 6 and 7 are gated on the gateway deploy and ideally the December Workers Paid move.

---

## 5. Upgrade test checklist (every release in this plan)

Install previous release → seed → upgrade → verify:

- [ ] Chats open with full history, old messages show the right speaker
- [ ] Signed in (gateway + blaze-auth/argus-auth), cloud sync runs
- [ ] Settings/prefs unchanged (model tier, voice, theme, tokens)
- [ ] Accessibility service still **on**; phone control works
- [ ] Still the default digital assistant; "Hey Argus" and "Hey Blaze" wake
- [ ] Home-screen widget still placed and updating; QS tile still present
- [ ] Pinned shortcuts / launcher icon open the app
- [ ] A routine/reminder scheduled before upgrade fires after it (WorkManager)
- [ ] Music/Video library still lists old downloads; new downloads land in `…/Argus`
- [ ] Notification channel settings preserved
- [ ] `blaze://add-source` and `argus://add-source` both open; App Link from the old and new host both open
- [ ] Built-in docs search works (`argus_docs` rebuilt)
- [ ] Router (`lib*router.so`) starts on 127.0.0.1:20128
- [ ] Full unit suite green on GitHub `build.yml`; `check-no-blaze` green

---

## 6. Rollback

- Phases 1–3: plain revert.
- Phase 4: shims make it reversible; a revert restores the old classes at the same FQCNs.
- Phase 5: old artefacts are never deleted in the migrating release, so the previous APK still reads them; writes of new-only formats (e.g. `ARGUS` role) are deferred one release for exactly this reason.
- Phase 6: gateway accepts both throughout; app rollback is safe.
- Phase 7: old Workers/hosts stay live as proxies/redirects until Phase 8.
