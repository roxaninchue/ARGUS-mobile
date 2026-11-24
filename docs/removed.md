# Removed code

One line per removal, newest first.

- 0.55.13: Internet Archive and Blender movie sources (`ArchiveFeatureFilms`, `BlenderOpenMovies`, `BlenderIds`, `ArchiveMovieParse`, store `ArchiveSources.kt` with `ArchiveFilmsSource` / `BlenderMoviesSource` / `ArchiveParse`), the preinstalled "Blaze Official" repo (`seedOfficial`, `assets/movies/blaze-official`), the archive branch of `MovieDownloads.start`, the Movies phrase routes in `InAppRoute` and `MovieTools.userAllows` / `route`. Owner ask: YouTube and open-source providers only, no trigger words.
- v0.45.0: `Agent.Session.runDuo`, `duoAll`, `duoTeam`, `duoSay`, `duoType` (about 125 lines). No caller since the live @all moved to `runConversation` → `duoBoth`; the removed `duoAll` was the old uncapped version (no `Duo.PARALLEL_LIMIT_MS`, no text-call handling), a trap if wired back.
- v0.45.0: `ui/SettingsV17.kt` `AutoRouterSection` (the free-stack chain, "Use my free stack", per-model cooldowns). It had no caller, so it was never on screen; `FreeRouter` itself stays because `LlmClient` (OpenRouter alternatives) and `Brains` still call it.
