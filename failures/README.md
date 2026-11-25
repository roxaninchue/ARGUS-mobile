# Failures log

Started 2026-11-24 (Finn: "we'll also start documenting failures neatly"). One file per failure that reached a phone, a
build or a release: `failures/YYYY-MM-DD-short-slug.md`, newest at the top of the index below. Kept outside `docs/` so
these notes are not bundled into the app's help search.

**When to write one:** a crash or broken flow on a phone, a red CI build, a bad deploy, or a fix that had to be undone.
Write it the same day the cause is known; update it when the fix is confirmed on the phone.

## Template

```
# <What broke, in plain words> (<version>, <date>)

Status: open | fixed in <version> (awaiting phone check) | confirmed fixed on <date>

## What the user saw
## Versions
Last good: ... Broken: ... Fixed: ...
## How we found it
(the evidence: crash report, CI log, bisect; what was tried and ruled out)
## Root cause
## Fix
(commit, files, why this fix)
## Guard
(the test, rule or check that keeps it from coming back)
## Lessons
```

## Index

| Date | Failure | Versions | Status |
|---|---|---|---|
| 2026-11-25 | [Build #144 red: 14 tests still expected phone control](2026-11-25-red-build-144-stale-tests.md) | 0.55.18 | fixed on fix/gate-green, rebuild pending |
| 2026-11-24 | [Build #141 red: a square corner on the new genre page](2026-11-24-red-build-141-square-shape.md) | 0.55.17 | fixed, build #142 green |
| 2026-11-24 | [Bots and teams lost after clearing storage](2026-11-24-bots-lost-after-clearing-storage.md) | all before 0.55.17 | gap closed in 0.55.17; data not recoverable |
| 2026-11-24 | [Crash right after sign-in: VerifyError on the chat loop](2026-11-24-verifyerror-after-sign-in.md) | broke 0.55.14, fixed 0.55.16 | fixed in 0.55.16, phone check pending |
