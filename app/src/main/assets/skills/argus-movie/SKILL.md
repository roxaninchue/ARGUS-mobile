---
name: argus-movie
description: Argus Movie help - finding and playing movies, adding or fixing provider sources, Jellyfin, subtitles and downloads. Use when the user asks how to get movies, wants a provider or source added, or a source fails.
tags: [movies, video, providers, sources, jellyfin, youtube]
version: 1
---

# Argus Movie

Argus Movie is the movie and video app inside Argus (the view called "Movie"; it was "Argus Video" before 0.55.13).
Tabs: Home, Search, Library, Sources.

## Where movies come from
- **Built in:** YouTube (search, full movies, trailers), the videos on this phone, and the user's Jellyfin server.
- **Added by the user:** open-source provider repos (Sources › +). Argus suggests none and checks none; the user is
  responsible for what they add. Internet Archive and Blender were removed in 0.55.13.

## Tools (catalog tools: find them with find_tools)
- `movies`: play, search, continue, watchlist. Added providers join with `use_providers=true` or `source=<provider name>`.
- `movie_sources`: list, add, remove, enable, disable, test, update, open. You add sources yourself; the user doesn't have to.
- `video_ops`: the player that's open now (subtitles, AI subtitle translation, recap, series, autoplay / data saver).

## Adding a source the user named
1. A source is a **repo folder with manifest.json** (provider layout: `manifest.json` plus `dist/<id>/posts.js`,
   `meta.js`, `stream.js`, `catalog.js`). It accepts `owner/repo`, a GitHub or GitLab repo link, or a raw folder URL.
2. A streaming website, an app's homepage or an APK page is **not** a source. If the user gives only a name or a
   website, find that project's provider repo with `web_search` ("<name> providers github manifest.json"), then
   `movie_sources add` with the repo. If nothing clear turns up, ask the user for the repo link. Never tell them to
   type a website into Add source.
3. After adding, `movie_sources test` one provider, then offer to play something with `movies`.
4. "No manifest.json found (404)" means the link isn't a provider repo, or the manifest is in another branch or folder:
   try the repo's main page link, or ask for the right one.

## Common fixes
- A provider shows Failing: `movie_sources test`, then `update`; turn it off if it keeps failing.
- Nothing plays from a provider: try another result or another provider; HLS / DASH streams can't be downloaded.
- Jellyfin: added in Sources with the server address and sign-in.
