# Renaming to Argus (0.52.1 user-facing, 0.52.4 everything)

Theme: omniscience (Argus Panoptes, the all-seeing). Brand tokens: docs/BRAND.md.

## One-line future rename
`app/src/main/java/com/blazeinchue/argus/BrandName.kt`: `NAME = "Argus"`, `UPPER = "ARGUS"`. Every Kotlin user-facing string,
prompt and label uses `${BrandName.NAME}` / `${BrandName.UPPER}`. Non-Kotlin places that can't read the constant, and must be
changed by hand: `res/values/strings.xml`, `res/layout/widget_argus.xml`, the manifest `android:label`s,
`assets/persona-v1.json`, `assets/skills/**/SKILL.md` (then the hashes in `assets/skills/index.json`),
`assets/specialists/*.md`, `assets/store/catalog.json` (= `store/catalog.json`), `app/argus-docs/about.md`.

## 0.52.1: what users saw change
- App / launcher label, activity labels (Argus Camera, Argus Phone, Argus Terminal, Argus Music, Argus Video…), strings,
  notifications, onboarding, About, Spotlight, the Terminal banner, the persona name and every system prompt ("You are
  Argus"), the built-in docs, skills and specialist briefs.
- Logo, avatars and icons: the silver Argus A (`ui/ArgusMark.kt`), adaptive launcher icon, notification icon
  `ic_stat_argus.xml`, Android 12+ splash.
- Tiers: Insight / Oracle / Panoptes (ids and the `argus_family` wire values stay spark / flare / inferno).

## 0.52.4: the old name is gone from the code
- **New app identity.** `applicationId` and `namespace` are `com.blazeinchue.argus` (the id chosen by the archived
  identity work, tag `archive/feat-argus-identity-signing`). The Kotlin packages moved from the old `com.<old>.assistant`
  to `com.blazeinchue.argus` (app main / test / androidTest and `:storeapi`). It is a different Android app: an existing
  install of the old package can't update in place; uninstall it and install Argus fresh (chats, keys and settings don't
  carry over on the phone; synced data comes back from the account).
- **Everything inside the repo is renamed:** classes, files, resources (`Theme.Argus`, `@xml/argus_widget` …), databases
  and prefs (`argus.db`, `argus` prefs), folders (Music/Argus, Download/Argus …), intent actions
  (`com.blazeinchue.argus.WIDGET_ASK` …), task affinities, the FileProvider authority (`${applicationId}.files`), deep links
  (`argus://auth/cb`, `argus://add-source`, `argus://pair`), the `X-Argus-*` headers (app, on-phone router and
  `gateway/`), `argus_*` request fields, user agents, the skill folders (`argus-*`), `app/argus-docs`, the Go router
  (`argusrouter/`, module `github.com/blazeinchue/argusrouter`, `libargusrouter.so`, `ARGUS_HOME`, `ARGUS_KEYS`), the
  `ARGUS_SHOTS` switch, and the CLI's install path in the phone's Linux (`/usr/local/bin/argus`).
- The wake word is "Hey Argus" / "OK Argus" / "Wake up Argus" only; commands no longer accept the old name.

## Kept on purpose (they name things outside this repo)
- The company / account name **Blazeinchue** and everything under it: `blazeinchue.pages.dev`, the GitLab path
  `blazeinchue/BlazeMobile` and the other repos' names (`Blaze-agent`, `Blaze-dekstop`, `Blaze-cli`, `Blaze-mobile`,
  `Blaze-mobile-builds`).
- Deployed Cloudflare names: the hosts `ai.blaze-app.workers.dev`, `blaze-gateway.blaze-app.workers.dev`,
  `blaze-auth.blaze-app.workers.dev`; the gateway Worker / D1 database name `blaze-gateway` and the `blaze-auth` service
  binding in `gateway/wrangler.toml` and its scripts.
- GitHub Actions secret names `BLAZE_MOBILE_TOKEN`, `BLAZE_SIGNING_P12`, `BLAZE_SIGNING_PASS` (repo settings, not code).
- What Desktop and the CLI share with the phone: the AES-GCM associated data `blaze-keys-v1`, `blaze-skills-v1`,
  `blaze-sync-v1` (changing them makes synced keys / skills / data unreadable on the other side), the published CLI build
  name `blaze-linux-arm64`, and the CLI's own files in the phone's Linux (`~/.blaze/secrets.json` with the
  `blaze-account:session` key, `~/.blaze/desktop/account.json`, `~/.blaze/skills`).
- CHANGELOG history entries keep the wording of their time.

When those are renamed outside the repo (new Worker hosts, new repo names, new secrets, new CLI release), change the
matching constants here in the same release.
