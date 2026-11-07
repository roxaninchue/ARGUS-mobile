#!/bin/bash
# 0.52.3 ARGUS onboarding check (local, not the release gate), run inside the emulator by .github/workflows/home-intro-check.yml
# (replaces run-argus-spotlight.sh / ArgusSpotlightCheck): installs the debug app + test APK, runs SpaceOnboardingCheck (the
# welcome, a real tap on Get started, the one setup screen through every state, a real tap on Continue; three rounds, 50 ms
# main-thread monitor, limit 700 ms; round 1 is a warm-up) and pulls its screenshots.
set -uo pipefail
OUT=onboarding-out; mkdir -p "$OUT"; PKG=com.blaze.assistant
adb install -r -g app/build/outputs/apk/debug/app-debug.apk > "$OUT/install.txt" 2>&1
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk >> "$OUT/install.txt" 2>&1
adb logcat -c
timeout 900 adb shell am instrument -w -r -e class $PKG.smoke.SpaceOnboardingCheck $PKG.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/instrument.txt" 2>&1 || true
adb pull /sdcard/Android/data/$PKG/files/space-onboarding "$OUT/shots" > /dev/null 2>&1 || echo "no screenshots" > "$OUT/no-shots.txt"
adb logcat -d -v threadtime > "$OUT/logcat.txt"
{ echo "# ARGUS onboarding check"; grep -E "OK \(|FAILURES|Tests run|shortMsg|stack=" "$OUT/instrument.txt" | head -20
  grep "BlazeSpaceOnboarding" "$OUT/logcat.txt" | sed 's/^.*BlazeSpaceOnboarding: /  /'
  echo "- files: $(ls "$OUT" | tr '\n' ' ') shots: $(ls "$OUT/shots" 2>/dev/null | wc -l)"; } > "$OUT/SUMMARY.md"
cat "$OUT/SUMMARY.md"
grep -q "^OK (1 test)" "$OUT/instrument.txt"
