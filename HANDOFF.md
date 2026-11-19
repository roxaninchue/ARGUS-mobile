# Handoff: Argus Music + Argus Video (v0.30.1, branch `media-apps`)

## Done
- **Two in-app apps** (own task like Terminal/Browser): `mediaapps/MusicActivity.kt`, `mediaapps/VideoActivity.kt`, each with the Player | Library | Store pill (`AppPill` in `mediaapps/MediaUi.kt`, same look as Chat | Projects | Home). Opened from Views, `/music`, `/video`, open_view (`ui/Views.kt`, `ui/ChatScreen.kt`, `agent/Commands.kt`).
- **Music:** media3 `MusicService` (MediaSessionService): background play, notification + lock-screen + headset controls, audio focus, unplug pause, gapless, shuffle (tapped song first), repeat off/all/one, speed, sleep timer (minutes / end of song), last queue + position restored. Now playing: artwork-tinted background, seek, queue (long-press drag handle to reorder, ×), synced lyrics (LRCLIB, cached), like. Mini-player on Library/Store.
- **Music library** (`MediaLib.kt`): downloads + Argus Gallery audio, songs / liked / artists / albums / playlists (create, rename, delete, add, remove, move up/down; JSON in `filesDir/mediaapps`) / recently played / search; delete a download.
- **Video:** ExoPlayer, swipe seek / brightness / volume, double-tap ±10 s, fullscreen + rotation, PiP (auto on 12+, on leave 8–11), speed, embedded subtitles + audio track choice, resume position, Continue watching, library grid with delete.
- **Stores:** one `StoreSource` interface (`storeapi/` module). Sources: Jamendo (needs client_id), Internet Archive audio (CC/PD only), Internet Archive Feature Films, Blender open movies, YouTube (plug-in). Grid with picture/title/author/length/licence, preview sheet with licence + quality/track choice, Download, Play/Watch (stream).
- **Downloads** (`Downloads.kt`, pure state in `DownloadLogic.kt`): WorkManager foreground queue, 8 MB ranges, resume after drop / kill / reboot, backoff retries, URL refresh, free-space check, pause/resume/cancel/retry, progress notification, MediaMuxer merge/remux (`MediaFiles.kt`), publish to `Music/Blaze/Music` and `Movies/Blaze/Videos`.
- **Tests (JVM):** `app/src/test/.../mediaapps/MediaAppsTest.kt` (queue/shuffle/repeat, playlists, recents, resume, download state/resume/retry/JSON, artwork colour, LRC), `StoreSourcesTest.kt` (licences, Archive parsing on real fixtures, Jamendo, plug-in discovery), `youtube/src/test/.../YtParseTest.kt` + `YouTubeEngineTest.kt` (search/player parsing, quality selection, client fallback).

## YouTube plug-in: how it's wired (required since 2026-11-19)
- **In this repo again since 2026-11-19 (owner):** the old private plug-in repo became unreachable, so the plug-in was rebuilt from scratch in `youtube/` (committed, public). No checkout step, no secret: every workflow builds it from the normal checkout. Daily drift check: `youtube-daily.yml` (04:47 UTC: plug-in + media tests + release APK with a dex check for `YouTubeSource`, then a live smoke test against YouTube; opens issue "YouTube plug-in daily check failing" on failure).
- Everything YouTube (plug-in side) is in **`youtube/`** (Gradle module `:youtube`): `YtNet.kt` (clients, transport, InnerTube calls), `YtParse.kt` (search / player / captions parsing, quality picks), `YouTubeSource.kt` (`YouTubeSource` + the testable `YouTubeEngine`), `src/test/**` (fixtures, no network; `YouTubeLiveSmokeTest` only with `YT_LIVE=1`), `src/main/resources/META-INF/proguard/youtube.pro` (R8 keep rule).
- `settings.gradle.kts` always includes `:youtube` and stops with an error when `youtube/build.gradle.kts` is missing; `app/build.gradle.kts` always adds `implementation(project(":youtube"))`; `StoreSources.plugin(YOUTUBE_CLASS)` loads it by class name at runtime.
- There is no build without YouTube any more (owner, 2026-11-19): the no-YouTube variant and its CI job were removed.
- It depends only on `:storeapi`, kotlinx-coroutines and (compileOnly) org.json. The Store chips use the app's built-in `YouTubeStoreSource` (v0.33.0); the plug-in is found by class name (`StoreSources.plugin`).

## Licence decision
NewPipeExtractor (GPL-3.0) not used: incompatible with Argus's PolyForm Noncommercial 1.0.0 licence and the bundled proprietary Play services libraries. The YouTube module is Argus's own InnerTube client. YouTube downloading may breach YouTube's ToS; it's isolated for that reason. Recorded in THIRD_PARTY_NOTICES.md.

## Not done / next steps
1. Agent tools (`music_play`, `media_download`) via the tool framework (ToolRouter); optional in the brief.
2. Drag-reorder inside playlists (has Move up/down in the ⋮ menu; the queue has real drag).
3. External subtitle files (.srt next to a video), subtitle styling, casting, Android Auto, widget, equalizer, crossfade.
4. Device-wide music library (READ_MEDIA_AUDIO) — today the library is Argus's downloads + Gallery files only.
5. ShotsTest screenshots of the new screens.
6. YouTube streams were not verified end-to-end from here (the sandbox IP gets YouTube's bot wall on /player; search works). Needs a phone test; if all three clients fail, update the client versions in `YtClient.clients`.
7. Jamendo needs a client_id: set `JAMENDO_CLIENT_ID` as a repo Actions secret/env for CI builds, or paste one in the Music Store.

## How to proceed
- Build: `./gradlew testReleaseUnitTest :storeapi:test :youtube:test assembleRelease` (the plug-in is in `youtube/`; there is no build without it).
- Code map: STATUS.md section "media-apps".
