# Build #141 red: a square corner on the new genre page (0.55.17, 2026-11-24)

Status: fixed in 0.55.17 (rebuild running)

## What the user saw
No APK. Finn said build at 14:04 CAT; the build did not start until 14:2x and then failed unit tests.

## Versions
Last good build: #140 (0.55.16). Broken: #141 (0.55.17, sha 235d2da7). Fixed: next build of 0.55.17.

## How we found it
1. The first build commit (5172ba37) changed only Markdown files, and `build.yml` ignores `**/*.md` pushes, so no run
   started. A watcher on the GitHub API also hit the unauthenticated rate limit and saw nothing. Found by reading the
   workflow's `paths-ignore`; a code-touching commit (235d2da7) started run 141 (45393470564).
2. Run 141 compiled, then `:app:testReleaseUnitTest` failed: NoSquareTest, `MusicGenrePage.kt:70:
   RoundedCornerShape(0.dp)` for the genre page's cover header.

## Root cause
The 0.55.17 work was committed `[skip ci]` and never compiled or tested before the build (no Gradle in the sandbox), and
the new header used a square shape, which the app's design rule forbids.

## Fix
The header cover and its shade use rounded bottom corners (28 dp). MusicGenrePage.kt.

## Guard
NoSquareTest already caught it. Process: a build commit must touch a non-Markdown file, and build watchers read the
GitHub run page, not the rate-limited API.

## Lessons
Before a build commit, grep new UI code for the shapes NoSquareTest bans (RoundedCornerShape(0), RectangleShape,
CutCornerShape).
