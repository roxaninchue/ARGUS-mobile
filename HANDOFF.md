# Handoff: picking up Argus for Android (2026-11-22)

This is the one current handoff. Older handoffs are listed in [docs/README.md](docs/README.md) (archive section).

## 0. IN FLIGHT: permission trim (branch `chore/trim-permissions-2`, Draft MR into main)
Finn (2026-11-25): strip the permissions Argus no longer needs. Started from main `006817fa`. Every commit ends `[skip ci]`.

**Keep:** YouTube plug-in + Music/Video downloads (mediaapps/, storeapi/), Movies sources (movies/), the built-in VPN
proxy (browser VPN + userspace `wgproxy/`, no VpnService), All files access (`MANAGE_EXTERNAL_STORAGE`), the self-updater
(update/, `REQUEST_INSTALL_PACKAGES`, InstallReceiver), the Argus Phone in-app browser (browser/phone/, steps 1/1a/1b/2).
Also INTERNET, network/wifi state, foreground services (mediaPlayback, dataSync, specialUse, microphone), POST_NOTIFICATIONS,
WAKE_LOCK, RECEIVE_BOOT_COMPLETED (routines). Flagged but kept: mic/voice, camera, calendar, alarms/exact alarms,
biometric, READ_MEDIA_*, battery-optimisation exemption, MODIFY_AUDIO_SETTINGS, quick settings tile, voice interaction.

**Remove (status):**
- [x] App Store: appstore/, AppStorePage, StoreInstallReceiver, assets/store, store/, STORE_CLEANROOM.md, store_* tools, tool group, settings page + view, tests
- [x] Bank mode + WRITE_SECURE_SETTINGS: bank/, BankModePage, BankModeService, bank_mode tool, Awareness line, Jarvis/Overlay gates, settings page + Security section, tests
- [ ] SMS & calls + contacts (READ/SEND_SMS, CALL_PHONE, ANSWER_PHONE_CALLS, READ_CALL_LOG, READ_PHONE_STATE, READ/WRITE_CONTACTS, telephony feature)
- [ ] Overlays SYSTEM_ALERT_WINDOW (orb, bin, live task card, overlay chat)
- [ ] PACKAGE_USAGE_STATS, notification listener, WRITE_SETTINGS, ACCESS_NOTIFICATION_POLICY, USE_FULL_SCREEN_INTENT
- [ ] Location, BLUETOOTH_CONNECT, SET_WALLPAPER, REQUEST_DELETE_PACKAGES, termux RUN_COMMAND
- [ ] Tests, guard baselines, docs; compile + unit tests green

**How to continue:** branch from the MR head, keep removing, then
`./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:testDebugUnitTest --continue` (JDK 17 build,
JDK 21 for tests; see BUILDING.md), `node tools/check-no-blaze.mjs --update-baseline` (counts only go down),
`node tools/check-do-not-port.mjs`. Grep `app/src/test` for every removed tool name / class / settings id
(AGENTS.md rule 3). `policy/ActionClass.kt` must declare exactly the tools still defined (ToolRegistryTest).

## 1. Read first, in this order
1. [STATUS.md](STATUS.md), the "Current state" and "Standing rules" sections: what is built, what is released, what
   waits on Finn.
2. [AGENTS.md](AGENTS.md) and docs/MODEL-FIRST.md: no keyword routing, the model decides.
3. The top of [overhaul.md](overhaul.md) ("HARNESS MUSTS") and its handoff log (one line per session).
4. [CHANGELOG.md](CHANGELOG.md) for the last few versions; docs/LEAN-HARNESS.md for how a chat turn works today.

## 2. Rules that catch newcomers
- **Branch -> merge request -> green GitHub `Gate` run for the head sha -> merge** (Finn, 2026-11-23; AGENTS.md
  "Merge rule"). Create the branch with its first commit through the commits API (`"branch": "<new>",
  "start_branch": "main"`), open the MR. The mirror runs `.github/workflows/gate.yml` on each branch push; check
  `api.github.com/repos/roxaninchue/ARGUS-mobile/actions/runs?head_sha=<sha>` before merging. Merge with
  `PUT .../merge_requests/<iid>/merge` and a `merge_commit_message` ending in `[skip ci]`. GitLab CI is parked.
- **No APK builds until Finn says so** (standing rule, 2026-11-22 21:26 CAT): the merge commit into `main` carries
  `[skip ci]`, so the GitHub mirror's Build APK does not run. WIP commits on a branch may carry `[skip ci]`; the last
  one before the merge must run the Gate.
- Never push or merge on the GitHub mirror.
- No trigger words, no text routing, no local model, no keep-warm ping.
- `Agent.runLoop` is at the JVM's 64 KB method limit: add nothing inside it (a build once failed with
  `MethodTooLargeException`).
- Before cutting a behaviour, add a parity test. Tests read source files as text: grep `app/src/test` for every key,
  string or behaviour you remove or rename, and fix the tests in the same commit.
- Gateway changes are deployed only on Finn's word, and the gateway is a separate GitLab project.
- Do not touch `applicationId`, the `blaze://` deep-link scheme, or the in-app about doc outside a build commit
  (search-ranking tests read it).

## 3. Before every commit (two checks, seconds each)
- The rename guard: `node tools/check-no-` + the old product name + `.mjs`, run from the project root. No file may
  gain an occurrence of the old name; the total must stay at or below `tools/rename/baseline.json`.
- `node tools/check-do-not-port.mjs`: must report 0 hits.
Before a **build** commit also update CHANGELOG.md, STATUS.md, the overhaul.md handoff log, the about doc's "New in"
lines, the version pin in `SpaceWiringTest`, the rename-guard baseline (only downwards) and the evals (`evals/README.md`).

## 4. Building and testing locally
- Full local Gradle needs JDK 17/21, Android SDK 34 and Go 1.26; the steps are in [BUILDING.md](BUILDING.md) and, for
  an aarch64 sandbox without KVM, docs/BUILD-LOCAL.md.
- Most sessions instead compile the changed classes and their tests with `kotlinc` + JUnit 4 against small stubs and
  run just those tests; each STATUS entry lists what was run and what was not. CI is the full check.
- The rename guard and do-not-port check run with plain Node; `evals/` runs by hand with Node 22 + Python 3.

## 5. CI (GitHub Actions on the mirror)
- GitLab `main` is mirrored to GitHub `roxaninchue/ARGUS-mobile` within minutes; "Build APK" (`build.yml`) runs on a
  push unless the commit says `[skip ci]` or only touches docs. A new push cancels the running round.
- Find the run: `curl -s "https://api.github.com/repos/roxaninchue/ARGUS-mobile/actions/runs?per_page=3"`, match
  `head_sha`; job names and results are public at `/actions/runs/<id>/jobs`. It takes about 20–25 minutes.
- Job logs need a GitHub token. On failure the run uploads `buildlog-<sha>` (build.log + test reports) and writes the
  failed tests into the run summary. Fetch artifacts without a sign-in through nightly.link:
  `https://nightly.link/roxaninchue/ARGUS-mobile/actions/runs/<run-id>` (lists them) or
  `https://nightly.link/roxaninchue/ARGUS-mobile/actions/artifacts/<artifact-id>.zip`.
- Artifacts of a green run: `argus-debug-signed-<sha>` (test-signed APK, never the release key), `mapping-<sha>`
  (R8 map for reading phone crash stacks), `shots-<sha>`.

## 6. Committing (GitLab commits API)
Plain `git push` over HTTPS does not work from the Hark sandbox. Commit through the API instead (project id 87806466,
the `argus-mobile` project):
```
POST https://gitlab.com/api/v4/projects/87806466/repository/commits
{"branch": "main", "commit_message": "what changed [skip ci]",
 "actions": [{"action": "update", "file_path": "STATUS.md", "content": "..."}]}
```
Actions are `create`, `update`, `move` (with `previous_path`) and `delete`. Read a file from `main` with
`GET .../repository/files/<url-encoded path>/raw?ref=main` and compare it with your copy before you overwrite it.
Prefer `move` over `delete` for docs. The Hark workspace keeps a helper for this (`work/glpush.py`).

## 7. Open items (2026-11-22)
- Phone check of 0.55.5 by Finn.
- 0.55.6 is on `main` and its auto-started CI run (45060697735) went green, but it is unreleased: send nothing until Finn says.
- Gateway: remove the `trimTools` word list only with Finn's word; the lane recheck interval change is not done.
- Living UI 0.3.0: plan written (docs/overhaul/living-ui-0.3.0-plan.md), not started.
- The overhaul plan (D1–D12) is not approved; the ArgusXHark port is stopped.

---

## Archived below: the media apps handoff (v0.30.1, branch `media-apps`)

Kept here verbatim (only the heading levels changed) because the rename guard counts per file path, so this text cannot move to a new file without
raising the count. Music, Video and the Store shipped long ago; see CHANGELOG.md for what changed since.

### Handoff: Argus Music + Argus Video (v0.30.1, branch `media-apps`)

#### Done
- **Two in-app apps** (own task like Terminal/Browser): `mediaapps/MusicActivity.kt`, `mediaapps/VideoActivity.kt`, each with the Player | Library | Store pill (`AppPill` in `mediaapps/MediaUi.kt`, same look as Chat | Projects | Home). Opened from Views, `/music`, `/video`, open_view (`ui/Views.kt`, `ui/ChatScreen.kt`, `agent/Commands.kt`).
- **Music:** media3 `MusicService` (MediaSessionService): background play, notification + lock-screen + headset controls, audio focus, unplug pause, gapless, shuffle (tapped song first), repeat off/all/one, speed, sleep timer (minutes / end of song), last queue + position restored. Now playing: artwork-tinted background, seek, queue (long-press drag handle to reorder, ×), synced lyrics (LRCLIB, cached), like. Mini-player on Library/Store.
- **Music library** (`MediaLib.kt`): downloads + Argus Gallery audio, songs / liked / artists / albums / playlists (create, rename, delete, add, remove, move up/down; JSON in `filesDir/mediaapps`) / recently played / search; delete a download.
- **Video:** ExoPlayer, swipe seek / brightness / volume, double-tap ±10 s, fullscreen + rotation, PiP (auto on 12+, on leave 8–11), speed, embedded subtitles + audio track choice, resume position, Continue watching, library grid with delete.
- **Stores:** one `StoreSource` interface (`storeapi/` module). Sources: Jamendo (needs client_id), Internet Archive audio (CC/PD only), Internet Archive Feature Films, Blender open movies, YouTube (plug-in). Grid with picture/title/author/length/licence, preview sheet with licence + quality/track choice, Download, Play/Watch (stream).
- **Downloads** (`Downloads.kt`, pure state in `DownloadLogic.kt`): WorkManager foreground queue, 8 MB ranges, resume after drop / kill / reboot, backoff retries, URL refresh, free-space check, pause/resume/cancel/retry, progress notification, MediaMuxer merge/remux (`MediaFiles.kt`), publish to `Music/Blaze/Music` and `Movies/Blaze/Videos`.
- **Tests (JVM):** `app/src/test/.../mediaapps/MediaAppsTest.kt` (queue/shuffle/repeat, playlists, recents, resume, download state/resume/retry/JSON, artwork colour, LRC), `StoreSourcesTest.kt` (licences, Archive parsing on real fixtures, Jamendo, plug-in discovery), `youtube/src/test/.../YtTest.kt` (search/player parsing, quality selection).

#### YouTube plug-in: how it's wired, how to drop it
- **Since the move to a private repo:** the plug-in's code lives in **<owner>/Blaze-mobile** (private) and is checked out into `youtube/` (gitignored here) at build time. CI: `build.yml` step "Check out the YouTube plug-in" with secret `BLAZE_MOBILE_TOKEN` (fine-grained PAT, Contents: read on Blaze-mobile); no secret / failed checkout → builds without YouTube, with a warning + run summary line. Local: `git clone https://github.com/<owner>/Blaze-mobile.git youtube`. Daily drift checks: `youtube-daily.yml` here (04:47 UTC, full APK with the plug-in, opens issue "YouTube plug-in daily check failing") and Blaze-mobile's `sync.yml` (04:17 UTC tests, weekly APK; needs Actions billing on the private repo).
- Everything YouTube is in **`youtube/`** (Gradle module `:youtube`): `YtClient.kt`, `YtParse.kt`, `YtQuality.kt`, `YouTubeSource.kt`, `src/test/**`, `src/main/resources/META-INF/proguard/blaze-youtube.pro`.
- `settings.gradle.kts` includes `:youtube` only if `youtube/build.gradle.kts` exists; `app/build.gradle.kts` adds `implementation(project(":youtube"))` only if that project exists; `StoreSources.plugin(YOUTUBE_CLASS)` loads it by class name at runtime.
- **To ship without YouTube: delete the `youtube/` folder.** No other file changes. CI job `build-without-youtube` proves it on every push (tests + assembleRelease + "no YouTube classes in the APK").
- Moving it to a private repo: keep the folder layout and check it out into `youtube/` before building (e.g. a submodule or a CI checkout step). It depends only on `:storeapi`, kotlinx-coroutines and (compileOnly) org.json.

#### Licence decision
NewPipeExtractor (GPL-3.0) not used: incompatible with Argus's PolyForm Noncommercial 1.0.0 licence and the bundled proprietary Play services libraries. The YouTube module is Argus's own InnerTube client. YouTube downloading may breach YouTube's ToS; it's isolated for that reason. Recorded in THIRD_PARTY_NOTICES.md.

#### Not done / next steps
1. Agent tools (`music_play`, `media_download`) via the tool framework (ToolRouter); optional in the brief.
2. Drag-reorder inside playlists (has Move up/down in the ⋮ menu; the queue has real drag).
3. External subtitle files (.srt next to a video), subtitle styling, casting, Android Auto, widget, equalizer, crossfade.
4. Device-wide music library (READ_MEDIA_AUDIO) — today the library is Argus's downloads + Gallery files only.
5. ShotsTest screenshots of the new screens.
6. YouTube streams were not verified end-to-end from here (the sandbox IP gets YouTube's bot wall on /player; search works). Needs a phone test; if all three clients fail, update the client versions in `YtClient.clients`.
7. Jamendo needs a client_id: set `JAMENDO_CLIENT_ID` as a repo Actions secret/env for CI builds, or paste one in the Music Store.

#### How to proceed
- Build: `./gradlew testReleaseUnitTest :storeapi:test :youtube:test assembleRelease` (`:youtube:test` only with Blaze-mobile cloned into `youtube/`).
- Code map: STATUS.md section "media-apps".
