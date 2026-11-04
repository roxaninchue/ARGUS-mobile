# Blaze → Argus (0.52.1)

Theme: omniscience (Argus Panoptes, the all-seeing). Brand tokens: docs/BRAND.md.

## One-line future rename
`app/src/main/java/com/blaze/assistant/BrandName.kt`: `NAME = "Argus"`, `UPPER = "ARGUS"`. Every Kotlin user-facing string,
prompt and label uses `${BrandName.NAME}` / `${BrandName.UPPER}` (≈1,650 literals rewritten by `work/rename_argus.py`,
which only touches the capitalised word inside string literals). Non-Kotlin places that can't read the constant, and must be
changed by hand (or by re-running the script with a new name): `res/values/strings.xml`, `res/layout/widget_blaze.xml`, the
manifest `android:label`s, `assets/persona-v1.json`, `assets/skills/**/SKILL.md`, `assets/specialists/*.md`,
`assets/store/catalog.json`, `app/blaze-docs/about.md`.

## What changed (user-facing)
- App / launcher label, activity labels (Argus Camera, Argus Phone, Argus Terminal, Argus Music, Argus Video…), strings,
  notifications (the conversation Person is "Argus"), onboarding ("Welcome to ARGUS"), About (wordmark), the top pill
  (wordmark outside a project), Spotlight, the Terminal banner ("Welcome to Argus Terminal!"), the persona name and every
  system prompt ("You are Argus"), the built-in docs (blaze-docs/about.md), skills and specialist briefs.
- "Blaze X" product names → "Argus X": Phone, Music, Terminal, Store, Cloud, Camera, Video, Stage, Link, Desktop, Gallery,
  Browser, Free… (all via the constant).
- Wake word: "Hey Argus" / "OK Argus" / "Wake up Argus" (the old "Hey Blaze" phrases still wake it).
- Logo, avatars and icons: the silver Argus A (`ui/ArgusMark.kt`) replaces the mascot drop in BrandMark / BlazeAvatar /
  DuoEmblem; new adaptive launcher icon (background, foreground, monochrome), round icon, PNG mipmaps for every density,
  notification icon `ic_stat_blaze.xml`, Android 12+ splash (`values-v31`, `values-night-v31`).
- Theme and type: see docs/BRAND.md (Argus Black / Cobalt / Electric / Silver / White, Inter Light → Bold).
- Tiers: Spark → **Insight**, Flare → **Oracle**, Inferno → **Panoptes** (fast → balanced → deepest), with an icon each.
  Ids and the `blaze_family` wire values stay spark / flare / inferno (the gateway's tiers.ts is unchanged).
  Migration: `ModelChoice.migrate()` rewrites a pick saved as a label ("Spark", "Insight", "Panoptes"…) to its id on load;
  `ModelChoice.of()` accepts ids, new labels and old labels. TiersTest.argusLabelsKeepIdsAndMigrateSavedPicks.

## Deliberately kept (changing them breaks installs, sync or sign-in)
- applicationId and Kotlin package `com.blaze.assistant`; class names (BlazeApp, BlazeTopPill, Theme.Blaze…), resource
  names (ic_stat_blaze, widget_blaze, Theme.Blaze).
- Database, file, pref and folder names: blaze.db, `blaze` prefs, bg_agents.json, task_records.json, and the shared-storage
  folders Music/Blaze, Movies/Blaze, Download/Blaze, Pictures/Blaze (`SaveFileAndroid.FOLDER` etc.): existing downloads stay
  findable.
- Gateway URLs and hosts (blaze-gateway…, blazeinchue.pages.dev), the `X-Blaze-*` headers, `blaze_*` request fields
  (`blaze_family`), User-Agent strings (Blaze-Android, Blaze-Store), the Jellyfin client id ("Blaze" / "Blaze Android").
- Repo names (Blazeinchue/BlazeMobile, Blaze-agent), the CLI binary name, log tags, the lowercase id `blaze` (persona id,
  Duo.Who id, deep links).
- Spark / Flare / Inferno ids (see Tiers).
- The on-screen pointer (phone/Mascot.kt geometry) is still the old drop shape; it is a cursor, not the logo. Follow-up if wanted.

## Not verified
- Not run on a device. Whether "argus" is in the offline wake-word model's vocabulary is unverified (a missing word is
  ignored by the grammar; "Hey Blaze" keeps working either way).
- Old chats keep "Blaze" where it was saved in their text; new messages say Argus.
