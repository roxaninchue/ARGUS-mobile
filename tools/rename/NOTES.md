# Package rename notes (branch feat/argus-rename)

2026-11-19 CAT. Base 7ca549d (main 54bebc4 + notch/glance bdfa5bd, a562339 + follow-up chips 7ca549d).
Target: Kotlin package, Gradle `namespace` and `applicationId` = `com.argus.assistant`; `rootProject.name = "Argus"`.

## Rerun from scratch
On a clean checkout of 7ca549d (scripts copied in from this folder), from the repo root:
```
python3 tools/rename/rename.py   # git mv source dirs + Blaze*.kt, then rewrite text (lexer: code / comment / string)
python3 tools/rename/post.py     # rootProject.name, signing prose, keep-blaze.txt, guard baseline
python3 tools/rename/commit.py   # 4 commits, pull --rebase --autostash + push -o ci.skip after each
```
rename.py printed: moved 1,505 files, renamed 24 Blaze*.kt files, edited 1,628 files. Idempotent (old -> new only).

## Rules (rename.py)
- `com.blaze.assistant` / `com/blaze/assistant` -> argus everywhere outside the skipped paths.
- Kotlin/Java/kts: `Blaze` -> `Argus` in code, comments and strings; lowercase `blaze` -> `argus` in code and comments only
  (lowercase inside string literals is kept: keys, ids, legacy regexes). Markdown/txt: both cases. Other files (xml,
  json, js, html, sh, yml, pro): capital `Blaze` and the package only.
- PROTECT (never rewritten): `X-Blaze-*`, `Blaze-<Word>` (UA, repos, release asset names), `Blaze/1`, kebab `blaze-*`
  and `*-blaze*`, snake `blaze_*` / `*_blaze*`, `blazeinchue`, `blazerouter`, `blaze://`, `blaze.<x>` (blaze.db,
  blaze.shots), `.blaze`, `@blaze`, `Music|Movies|Download(s)|Pictures|Documents/Blaze`, wake phrases, exact `"Blaze"` /
  `"blaze"` literals (BrandName.OLD, SaveFileAndroid.FOLDER, persona/prefs ids), `Blaze Desktop|CLI|Android`, `CN=Blaze`,
  any `BLAZE` token (Role/Who/Side/Mode.BLAZE serialised by name, BLAZE_SHOTS set by CI).
- KEEP_IDS: BlazeButton, BlazeColors, BlazeTheme (Argus twins exist in ui/argus), BlazeSpaceHome (logcat tag grepped by
  .github/workflows/home-focus-check.yml).
- Skipped paths: .git, gateway/, blazerouter/, wgproxy/, keystore/, .github/, evals/, docs/rename/, tools/rename/,
  assets/legal/, jniLibs, CHANGELOG.md, docs/RENAME-ARGUS*.md, tools/check-no-blaze*.mjs, THIRD_PARTY_NOTICES.md, LICENSE.md.

## Counts
- Rename guard (`node tools/check-no-blaze.mjs`, old allowlist): 17,829 before -> 3,441 after; with the new allowlist
  (keep-blaze.txt rewritten by post.py) 2,642; +35 for the overhaul.md Deviations rows that name the kept items = 2,677 baseline.
- Raw `grep -rIoi blaze` (whole repo incl. gateway/, blazerouter/, .github/): 20,100 -> ~7,000.
- Leftover `com.blaze.assistant` / `com/blaze/assistant` outside the kept list: 0 (remaining hits: .github/workflows (not
  edited, owner), THIRD_PARTY_NOTICES (history line), tools/check-no-blaze.test.mjs (test input), CHANGELOG history,
  docs/RENAME-ARGUS*, tools/rename/).

## Checks
- `node tools/check-no-blaze.mjs`: pass (baseline ratcheted). `node tools/check-no-blaze.test.mjs`: pass.
- `node tools/check-do-not-port.mjs`: 0 hits, 0 register mismatches. `node tools/check-do-not-port.test.mjs`: 2/2.

## Tests (kotlinc 2.0.21, JDK 17, `tools/rename/run_tests.py`; stubs = tools/jvmtest/stubs copied to /tmp with
Settings.str default + int/secret helpers, plus a TurnContextSource stub; nothing of that committed)
Pass: OnboardingLandscapeTest 3/3, SetupChecklistTest 7/7, SpaceWiringTest 17/17, NotchOverlayTest 7/7, GlanceTest 11/11,
GlanceShelfTest 4/4, TeamStoreTest 14/14, StageReducerTest 16/16, StageShelfTest 5/5, StagePersistTest 5/5,
StageLayersTest 5/5, StageTimerTest 9/9, StagePerfTest 2/2 = 105/105 in 13 classes.
Not run (closure reaches Android code, no android.jar here): QuickRepliesTest (ReplyParts lives in agent/Agent.kt),
ArgusThreadTest and ProactiveDialTest (via QuickReplies/ClaimGuard -> Secrets, LookupLane, SecretVault),
ArgusDocsTest (84+ files and the Gradle-generated docs dir), StageToolsTest, StageBrowserCardTest (unfinished closure),
StageMusicLogicTest / StageCardBasicsTest / StageShotsTest (Compose / Robolectric).
ArgusThreadTest's earlier 2 failures: its source needles quoted `blaze()` (Notifier's Person builder, renamed to
`argus()` in code while rename.py keeps lowercase inside strings). Fixed in the test needles by post.py (same for
DuoAll20Test); every positive `contains("...")` needle in ArgusThreadTest, QuickRepliesTest, ProactiveDialTest and
DuoAll20Test was checked by script to exist in main sources.

## Owner decisions / flags
- applicationId com.argus.assistant installs BESIDE the old Blaze app (no update path, no data, grants, widgets carried).
- assetlinks.json on blazeinchue.pages.dev lists com.blaze.assistant (not in this repo): App Links for /add-source fail
  until it also lists com.argus.assistant with the signing cert.
- A GitHub build of this branch would release-sign it; build.yml was not edited (owner's "build"), and its shot-test
  filters (`com.blaze.assistant.ui.ShotsTest*` …) now match nothing; home-focus-check.yml / youtube-daily.yml /
  mobile-build.yml name the old package too.
- :youtube (private Blaze-mobile) implements com.blaze.assistant.mediaapps.store.* from :storeapi; must be renamed with it.
- Persisted keys kept although a new applicationId starts with empty data: chosen so a later switch back to the old
  applicationId (or a data import) keeps working.

## Next steps
- Owner: decide the .github workflow updates (build.yml is the owner's "build" call), assetlinks.json on
  blazeinchue.pages.dev, the :youtube plug-in rename, and whether a build of this branch may be signed as a new app.
- With Gradle/CI (owner's call): `./gradlew :app:testDebugUnitTest` on this branch, then the shot tests (their CI filters
  must name com.argus.assistant first).
- Phase 3 follow-up: merge BlazeButton / BlazeColors / BlazeTheme into the ARGUS kit, then drop them from KEEP_IDS.

## Workflows (2026-11-19)
- build.yml, home-focus-check.yml, mobile-build.yml, youtube-daily.yml now name com.argus.assistant (Blaze050ShotsTest -> Argus050ShotsTest). The branch is NOT yet added to build.yml triggers; that waits for the owner's "build".
- youtube-daily.yml dex check still looks for Lcom/blaze/assistant/mediaapps/youtube/YouTubeSource; because that class lives in the private :youtube plug-in, which is not renamed yet. Change it to Lcom/argus/... when the plug-in is renamed.
- Still outside the repo: assetlinks.json on blazeinchue.pages.dev must add com.argus.assistant + signing cert.
