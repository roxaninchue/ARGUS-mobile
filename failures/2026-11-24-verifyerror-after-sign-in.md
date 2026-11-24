# Crash right after sign-in: VerifyError on the chat loop (0.55.14, 2026-11-24)

Status: fixed in 0.55.16 (commit `44dde6ab`, versionCode 225); phone check on Finn's Samsung pending.

## What the user saw
Finn, 24 Nov 10:14 CAT: after signing in (Google, then GitHub after clearing storage) Argus closed at once, every start.
"Send crash report" never showed, so no log could be shared. Sign-in itself worked on 0.55.12.

## Versions
- Last good: 0.55.12 (versionCode 221, `8e74c103`, GitHub run 45297042016).
- Broken: 0.55.14 (223, `db37b79`, run 45349728267) and 0.55.15 (224, `3241ab4c`, run 45360260837).
- Fixed: 0.55.16 (225, `44dde6ab`).
- Phone: Samsung SM-A125F (Galaxy A12), Android 12.

## How we found it
1. Diff review of 0.55.12 -> 0.55.14 (manifest, routing, movie sources and the Keystore-sealed tokens, skills seeding,
   calendar, sync paths): nothing that could crash at start. Cloud sync ruled out (Finn doesn't use it).
2. 0.55.15 added a crash screen (`CrashReportActivity`): when a launch dies within 20 s with a crash on record, the next
   start shows the trace with Share / Copy instead of running Argus's start-up.
3. Finn shared the report (11:05): `java.lang.VerifyError: Verifier rejected class x4.z2: java.lang.Object x4.z2.y0(I7.c)
   failed to verify ... [0x11C85] copy-reference v13<-v508 type=Undefined`.
4. The run's R8 mapping (`mapping-3241ab4c...` artifact) gave `x4.z2` = `com.blaze.assistant.agent.Agent$Session`,
   `y0` = `runLoop(Continuation)`, and showed `runLoop$pickTools`, `runLoop$call`, `runLoop$model`, `runLoop$callPlain`
   all inlined into it.

## Root cause
`Agent.Session.runLoop` is a very large suspend function (about 950 source lines, Agent.kt from line 3362). R8 (AGP
8.5.2, release build with `proguard-android-optimize.txt`) inlined its local functions into it. With the small additions
of 0.55.13/0.55.14 the compiled method crossed the point where Android's bytecode verifier rejects it. A rejected method
makes the whole class fail to load, so the first use of the chat session (right after sign-in) threw `VerifyError`.
The source of runLoop barely changed between the good and bad builds, which is why reading the diff found nothing.

## Fix
`app/proguard-rules.pro`:
```
-keepclassmembers,allowobfuscation,allowshrinking class com.blaze.assistant.agent.Agent$Session {
    *** runLoop$*(...);
}
```
A kept method is never inlined, so runLoop's local functions stay separate methods and runLoop shrinks back.

## Guard
- `RunLoopKeepTest` fails if the keep rule is removed.
- The crash screen (0.55.15) stays: any start-up crash loop now shows its trace on the phone.

## Lessons
- A crash that appears with no related source change can come from the optimiser; get the trace and the R8 mapping
  before reading diffs.
- runLoop should be split into smaller functions (follow-up, not done): the keep rule buys room, it doesn't remove the
  risk of the method growing back past the limit.
- Every APK build should keep publishing its `mapping-<sha>` artifact; it is what turned `x4.z2.y0` into a name.
